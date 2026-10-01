package mihon.domain.source.health

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Per-source circuit breaker. Failed work never cancels another source or consumes its retry budget. */
class SourceHealthMonitor(private val clock: () -> Long = System::currentTimeMillis) {
    enum class State { HEALTHY, DEGRADED, UNAVAILABLE }
    data class Health(val state: State = State.HEALTHY, val failures: Int = 0, val semanticFailures: Int = 0, val nextProbeAt: Long = 0)
    private val mutable = MutableStateFlow<Map<Long, Health>>(emptyMap())
    val states = mutable.asStateFlow()
    private val permits = ConcurrentHashMap<Long, Semaphore>()
    private val global = Semaphore(3)
    private val knownCatalogues = ConcurrentHashMap.newKeySet<Long>()
    fun expectCatalogue(id: Long) { knownCatalogues += id }
    fun expectsCatalogue(id: Long) = id in knownCatalogues
    fun health(id: Long) = states.value[id] ?: Health()
    fun discoverable(id: Long) = health(id).state != State.UNAVAILABLE
    fun due(id: Long) = clock() >= health(id).nextProbeAt
    @Synchronized fun restore(values: Map<Long, Health>) { mutable.value = values }
    @Synchronized fun degrade(id: Long) { mutable.value = mutable.value + (id to health(id).copy(state = State.DEGRADED)) }
    @Synchronized fun success(id: Long) { mutable.value = mutable.value + (id to Health()) }
    @Synchronized fun failure(id: Long, semantic: Boolean) {
        val old = health(id)
        val count = old.failures + 1
        val semanticCount = old.semanticFailures + if (semantic) 1 else 0
        val unavailable = semanticCount >= 3 || count >= 5
        val backoff = (60_000L * (1L shl (count - 1).coerceAtMost(9))).coerceAtMost(6 * 60 * 60_000L)
        mutable.value = mutable.value + (id to Health(if (unavailable) State.UNAVAILABLE else State.DEGRADED, count, semanticCount, clock() + backoff))
    }

    suspend fun <T> run(id: Long, timeoutMs: Long = 45_000, healthy: (T) -> Boolean = { true }, operation: suspend () -> T): T {
        var started = false
        try {
            return withTimeoutOrNull(timeoutMs) {
                permits.computeIfAbsent(id) { Semaphore(1) }.withPermit {
                    if (!due(id)) throw SourceCoolingDown(health(id).state)
                    global.withPermit {
                        started = true
                        operation().also { if (healthy(it)) success(id) else failure(id, true) }
                    }
                }
            } ?: throw SourceOperationTimeout(started)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SourceCoolingDown) {
            throw e
        } catch (e: SourceOperationTimeout) {
            if (e.started) failure(id, false)
            throw e
        } catch (e: Exception) {
            // Locked/unsupported content is a valid per-manga outcome, not source failure.
            val message = e.message.orEmpty().lowercase()
            if (!message.contains("locked") && !message.contains("not supported") && !message.contains("no chapters")) {
                val transient = e is java.net.SocketTimeoutException || e is java.net.UnknownHostException || e is java.net.ConnectException
                failure(id, !transient)
            }
            throw e
        }
    }

    class SourceCoolingDown(state: State) : IOException(if (state == State.UNAVAILABLE) "Source temporarily unavailable" else "Source retry is backed off; last known data preserved")
    class SourceOperationTimeout(val started: Boolean) : IOException(if (started) "Source operation timed out" else "Source busy; last known data preserved")
    companion object { val shared = SourceHealthMonitor() }
}
