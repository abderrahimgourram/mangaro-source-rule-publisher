package mihon.domain.source.health

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

class SourceHealthMonitorTest {
    @Test fun `semantic failures unavailable threshold bounded backoff and restoration are source isolated`() = runTest {
        var now = 0L
        val health = SourceHealthMonitor { now }
        var calls = 0
        for (attempt in 1..3) {
            assertThrows<IOException> { health.run(1) { calls++; throw IOException("invalid JSON representation") } }
            health.health(1).failures shouldBe attempt
            health.run(2) { "healthy" } shouldBe "healthy"
            assertThrows<SourceHealthMonitor.SourceCoolingDown> { health.run(1) { calls++; true } }
            calls shouldBe attempt
            now = health.health(1).nextProbeAt
        }
        health.health(1).state shouldBe SourceHealthMonitor.State.UNAVAILABLE
        health.health(2).state shouldBe SourceHealthMonitor.State.HEALTHY
        health.run(1) { "restored parser" } shouldBe "restored parser"
        health.health(1).state shouldBe SourceHealthMonitor.State.HEALTHY
        health.health(1).failures shouldBe 0
    }
    @Test fun `a random timeout does not mark unavailable and cancelled work does not alter health`() = runTest {
        val health = SourceHealthMonitor { 0L }
        assertThrows<java.net.SocketTimeoutException> { health.run(1) { throw java.net.SocketTimeoutException() } }
        health.health(1).state shouldBe SourceHealthMonitor.State.DEGRADED
        assertThrows<CancellationException> { health.run(2) { throw CancellationException("parent") } }
        health.health(2).failures shouldBe 0
    }
    @Test fun `supervised operations are bounded globally and separately serialized per source`() = runTest {
        val health = SourceHealthMonitor { 0L }
        var active = 0; var peak = 0
        supervisorScope {
            (1L..9L).map { id -> async { health.run(id) { active++; peak = maxOf(peak, active); delay(5); active--; id } } }.awaitAll()
        }
        peak shouldBe 3
        var perSource = 0; var sourcePeak = 0
        supervisorScope {
            (1..3).map { async { health.run(1) { perSource++; sourcePeak = maxOf(sourcePeak, perSource); delay(5); perSource-- } } }.awaitAll()
        }
        sourcePeak shouldBe 1
    }
    @Test fun `partial chapter results accumulate health failures without becoming successful checks`() = runTest {
        var now = 0L; val health = SourceHealthMonitor { now }
        repeat(3) {
            health.run(1, healthy = { result: String -> result == "COMPLETE" }) { "PARTIAL" } shouldBe "PARTIAL"
            now = health.health(1).nextProbeAt
        }
        health.health(1).state shouldBe SourceHealthMonitor.State.UNAVAILABLE
    }
}
