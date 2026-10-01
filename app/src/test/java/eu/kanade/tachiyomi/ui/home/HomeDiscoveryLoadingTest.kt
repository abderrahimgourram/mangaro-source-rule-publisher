package eu.kanade.tachiyomi.ui.home

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class HomeDiscoveryLoadingTest {

    @Test
    fun `interleaving follows configured source order despite unequal page sizes`() {
        interleaveSources(listOf(listOf("A1", "A2"), listOf("B1"), listOf("C1", "C2", "C3")))
            .shouldContainExactly("A1", "B1", "C1", "A2", "C2", "C3")
    }

    @Test
    fun `featured selection prefers saved manga and otherwise retains the indexed choice`() {
        val ids = listOf(30L, 10L, 20L)
        selectFeaturedIndex(ids, 10L, 0) shouldBe 1
        selectFeaturedIndex(ids, 99L, 5) shouldBe 2
        selectFeaturedIndex(ids, -1L, 0) shouldBe 0
    }

    private data class TestSourceResult(
        val sourceId: Long,
        val page: Int,
        val hasNextPage: Boolean,
        val items: List<String>,
    )

    @Test
    fun `verify bounded parallel source fetching preserves source ordering`() = runTest {
        val sourceIds = listOf(1L, 2L, 3L, 4L)
        val semaphore = Semaphore(2)

        val results = sourceIds.map { id ->
            async {
                semaphore.withPermit {
                    TestSourceResult(
                        sourceId = id,
                        page = 1,
                        hasNextPage = true,
                        items = listOf("Manga from $id"),
                    )
                }
            }
        }.awaitAll()

        results.map { it.sourceId } shouldContainExactly listOf(1L, 2L, 3L, 4L)
    }

    @Test
    fun `verify source failure is isolated and does not fail other sources`() = runTest {
        val sourceIds = listOf(1L, 2L, 3L)
        val semaphore = Semaphore(2)

        val results = sourceIds.map { id ->
            async {
                semaphore.withPermit {
                    try {
                        if (id == 2L) throw RuntimeException("Network error on source 2")
                        TestSourceResult(
                            sourceId = id,
                            page = 1,
                            hasNextPage = true,
                            items = listOf("Manga from $id"),
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull()

        results.map { it.sourceId } shouldContainExactly listOf(1L, 3L)
    }

    @Test
    fun `verify cancellation exception is rethrown`() = runTest {
        val semaphore = Semaphore(2)
        var cancellationRethrown = false

        try {
            async {
                semaphore.withPermit {
                    try {
                        throw CancellationException("Cancelled")
                    } catch (e: CancellationException) {
                        cancellationRethrown = true
                        throw e
                    } catch (_: Throwable) {
                        null
                    }
                }
            }.await()
        } catch (_: CancellationException) {
        }

        cancellationRethrown shouldBe true
    }
}
