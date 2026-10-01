package eu.kanade.tachiyomi.source.repair

import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.*
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

fun interface RuleTransport { suspend fun fetch(sourceId: Long): SignedRules? }

/** Static feed convention: HTTPS directory/{sourceId}.json, containing a signed envelope. */
class HttpsRuleTransport(private val directoryUrl: String, private val client: okhttp3.OkHttpClient) : RuleTransport {
    init {
        RulesFormat.publicHttps(directoryUrl)
        val uri = java.net.URI(directoryUrl)
        require(uri.rawQuery == null && uri.rawFragment == null) { "Rule feed must be a directory URL" }
    }
    override suspend fun fetch(sourceId: Long): SignedRules? {
        val url = "${directoryUrl.trimEnd('/')}/$sourceId.json"
        return withTimeoutOrNull(10_000) {
            client.newCall(Request.Builder().url(url).build()).awaitSuccess().use { response ->
                RulesFormat.publicHttps(response.request.url.toString())
                val source = response.body.source()
                source.request(384 * 1024L + 1)
                if (source.buffer.size > 384 * 1024) throw IOException("Oversized rule envelope")
                RulesFormat.json.decodeFromString<SignedRules>(source.readUtf8())
            }
        } ?: throw java.net.SocketTimeoutException("Rule feed timed out")
    }
}

/** Updates are serialized per source and globally bounded, independent of source health permits. */
class RuleRepairEngine(
    val store: RuleStore,
    private val transport: RuleTransport,
    private val health: mihon.domain.source.health.SourceHealthMonitor = mihon.domain.source.health.SourceHealthMonitor.shared,
    private val clock: () -> Long = System::currentTimeMillis,
    private val existingChapters: suspend (Long, SManga) -> List<SChapter> = { _, _ -> emptyList() },
) {
    private val locks = ConcurrentHashMap<Long, Mutex>()
    private val global = Semaphore(2)
    private val witnesses = ConcurrentHashMap<Pair<Long, String>, Set<String>>()

    private val cached = ConcurrentHashMap<Long, SourceRules>()
    private val loaded = ConcurrentHashMap.newKeySet<Long>()
    private val initializationLocks = ConcurrentHashMap<Long, Mutex>()
    suspend fun initialize(id: Long) = withContext(Dispatchers.IO) {
        initializationLocks.computeIfAbsent(id) { Mutex() }.withLock {
            if (id !in loaded) {
                val state = store.read(id)
                if (state.active == null) store.write(state)
                store.active(id)?.let { cached[id] = it }
                loaded.add(id)
            }
        }
    }
    fun active(source: HttpSource) = cached[source.id]?.let { RuleInterpreter(source.client, it) }
    fun hasActive(id: Long) = cached.containsKey(id)
    private fun refresh(id: Long) { store.active(id)?.let { cached[id] = it } ?: cached.remove(id) }

    private fun semantic(e: Exception): Boolean {
        if (e is java.net.SocketTimeoutException || e is java.net.UnknownHostException || e is java.net.ConnectException) return false
        val message = e.message.orEmpty().lowercase()
        return (e is eu.kanade.tachiyomi.network.HttpException && e.code in setOf(400, 403, 404, 410, 422, 500, 502, 503)) ||
            e is IOException && listOf("missing", "empty", "duplicate", "repeated", "incomplete", "invalid", "mismatch", "count", "chapter", "pages", "parse", "route", "representation", "cloudflare", "challenge", "not found", "field", "selector").any { it in message } ||
            e is kotlinx.serialization.SerializationException || e is IllegalArgumentException &&
            listOf("json", "field", "object", "element").any { it in message }
    }
    suspend fun <T> execute(source: HttpSource, native: suspend () -> T, operation: suspend (RuleInterpreter) -> T): T = withContext(Dispatchers.IO) {
        initialize(source.id)
        val selected = active(source)
        try { return@withContext if (selected == null) native() else operation(selected) }
        catch (e: CancellationException) { throw e }
        catch (original: Exception) {
            val message = original.message.orEmpty().lowercase()
            if (!semantic(original) || "not supported" in message || "locked" in message) throw original
            health.degrade(source.id)
            // The candidate also replays the failed operation before activation. Never mutate DB here.
            var replay: T? = null
            var succeeded = false
            val repaired = check(source, force = true) { candidate -> replay = operation(candidate); succeeded = true }
            if (repaired && succeeded) { health.success(source.id); @Suppress("UNCHECKED_CAST") return@withContext replay as T }
            if (selected != null) {
                // Do not overwrite another caller's newly activated version on this old failure.
                locks.computeIfAbsent(source.id) { Mutex() }.withLock {
                    if (store.active(source.id)?.revision == selected.rules.revision) { store.rollback(source.id); refresh(source.id) }
                }
            }
            throw original
        }
    }

    suspend fun check(source: HttpSource, force: Boolean = false, replay: suspend (RuleInterpreter) -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        locks.computeIfAbsent(source.id) { Mutex() }.withLock {
            initialize(source.id)
            val state = store.read(source.id)
            val now = clock()
            // Even reactive checks have a cooldown; a bad feed cannot cause a request storm.
            if (state.checkedAt > 0 && now - state.checkedAt < if (force) 60_000 else 30 * 60_000) return@withLock false
            store.write(state.copy(checkedAt = now))
            global.withPermit {
                try {
                    val envelope = transport.fetch(source.id) ?: return@withPermit false
                    val candidateRules = store.stage(source.id, envelope)
                    val candidate = RuleInterpreter(source.client, candidateRules)
                    withTimeoutOrNull(90_000) {
                        validate(source, candidate)
                        replay(candidate)
                        true
                    } ?: throw java.net.SocketTimeoutException("Candidate validation timed out")
                    store.activate(source.id)
                    refresh(source.id)
                    health.success(source.id)
                    true
                } catch (e: CancellationException) {
                    store.reject(source.id, permanent = false)
                    throw e
                } catch (e: Exception) {
                    // A bad/unreachable rule feed is never a source outage. Existing active/LKG is untouched.
                    val transient = e is java.net.SocketTimeoutException || e is java.net.UnknownHostException || e is java.net.ConnectException ||
                        e is eu.kanade.tachiyomi.network.HttpException && e.code >= 500
                    store.reject(source.id, permanent = !transient)
                    false
                }
            }
        }
    }

    private suspend fun validate(source: HttpSource, candidate: RuleInterpreter) {
        val first = candidate.catalogue("popular", 1)
        val sample = if (candidate.rules.validationQuery.isNotBlank()) {
            val produced = candidate.catalogue("search", 1, candidate.rules.validationQuery)
            produced.mangas.singleOrNull { (it.memo["id"] as? JsonPrimitive)?.content == candidate.rules.validationMangaId }
                ?: throw IOException("Signed validation sample identity not found")
        } else first.mangas.firstOrNull() ?: throw IOException("Candidate has no validation manga")
        if (first.hasNextPage) {
            val second = candidate.catalogue("popular", 2)
            if (second.mangas.isEmpty() || second.mangas.map { it.url }.toSet().intersect(first.mangas.map { it.url }.toSet()).isNotEmpty()) throw IOException("Repeated or missing catalogue page")
        }
        if (source.supportsLatest) {
            require("latest" in candidate.rules.operations)
            candidate.catalogue("latest", 1)
        }
        val search = candidate.catalogue("search", 1, sample.title)
        if (search.mangas.none { it.memo["id"] == sample.memo["id"] }) throw IOException("Candidate search identity mismatch")
        val detail = candidate.details(sample)
        // Small validation budget, full verification of this one series, never a partial sample marked COMPLETE.
        val chapters = candidate.chapters(detail, maxPages = 3)
        if (chapters.isEmpty() || chapters.size > 150) throw IOException("Candidate chapter sample unsuitable")
        val local = existingChapters(source.id, detail)
        if (local.isNotEmpty()) remember(source.id, detail, local)
        verifyExisting(source.id, detail.url, chapters)
        val pages = candidate.pages(chapters.first())
        // A valid URL alone is insufficient: request one bounded image through NetworkHelper's client.
        val imageRequest = Request.Builder().url(requireNotNull(pages.first().imageUrl)).header("Range", "bytes=0-511")
        candidate.rules.headers.forEach { (k, v) -> if ('{' !in v) imageRequest.header(k, v) }
        withTimeoutOrNull(10_000) {
            source.client.newCall(imageRequest.build()).awaitSuccess().use { response ->
                if (!response.header("Content-Type").orEmpty().lowercase().startsWith("image/")) throw IOException("Candidate image is not an image")
                val input = response.body.source()
                input.request(16)
                val bytes = input.readByteArray(minOf(16L, input.buffer.size))
                val magic = bytes.toString(Charsets.ISO_8859_1)
                if (!(magic.startsWith("\u00ff\u00d8\u00ff") || magic.startsWith("\u0089PNG\r\n\u001a\n") ||
                        magic.startsWith("GIF8") || magic.startsWith("RIFF") && magic.contains("WEBP") || magic.contains("ftypavif"))) {
                    throw IOException("Candidate image has invalid image bytes")
                }
            }
        } ?: throw IOException("Candidate image timed out")
    }
    private fun identity(id: Long, chapter: SChapter): String {
        val domain = tachiyomi.domain.chapter.model.Chapter.create().copy(url = chapter.url, memo = chapter.memo)
        return tachiyomi.domain.chapter.service.ChapterIdentity.remoteIds(domain, id).singleOrNull() ?: chapter.url
    }
    suspend fun rememberExisting(id: Long, manga: SManga, supplied: List<SChapter>) {
        val existing = if (supplied.isNotEmpty()) supplied else existingChapters(id, manga)
        if (existing.isNotEmpty()) remember(id, manga, existing)
    }
    fun remember(id: Long, manga: SManga, chapters: List<SChapter>) {
        if (witnesses.size >= 128) witnesses.keys.firstOrNull()?.let(witnesses::remove)
        witnesses[id to manga.url] = chapters.map { identity(id, it) }.toSet()
    }
    fun verifyExisting(id: Long, url: String, chapters: List<SChapter>) {
        val previous = witnesses[id to url].orEmpty()
        val current = chapters.map { identity(id, it) }.toSet()
        if (!current.containsAll(previous)) throw IOException("Candidate lost verified chapters")
    }
}
