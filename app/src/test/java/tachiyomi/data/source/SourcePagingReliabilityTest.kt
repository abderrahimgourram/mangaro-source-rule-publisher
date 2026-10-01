package tachiyomi.data.source

import androidx.paging.PagingSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga

class SourcePagingReliabilityTest {
    private fun pager(fetch: suspend (Int) -> MangasPage): BaseSourcePagingSource {
        val source = mockk<Source>()
        every { source.id } returns 123L
        val local = mockk<NetworkToLocalManga>()
        coEvery { local.invoke(any<List<Manga>>()) } answers { firstArg() }
        return object : BaseSourcePagingSource(source, local, mihon.domain.source.health.SourceHealthMonitor()) {
            override suspend fun requestNextPage(currentPage: Int) = fetch(currentPage)
        }
    }

    private fun params(page: Long = 1) = PagingSource.LoadParams.Refresh(page, 24, false)
    private fun manga() = SManga.create().apply { url = "/sample"; title = "Sample" }

    @Test
    fun `filtered empty page continues at correct remote page`() = runBlocking<Unit> {
        val requests = mutableListOf<Int>()
        val paging = pager { page ->
            requests.add(page)
            if (page == 1) MangasPage(emptyList(), true) else MangasPage(listOf(manga()), true)
        }
        val result = paging.load(params()) as PagingSource.LoadResult.Page
        requests shouldBe listOf(1, 2)
        result.nextKey shouldBe 3L
        result.data.size shouldBe 1
    }

    @Test
    fun `repeated catalogue produces a clean error`() = runBlocking<Unit> {
        val paging = pager { MangasPage(listOf(manga()), true) }
        paging.load(params())
        (paging.load(PagingSource.LoadParams.Append(2L, 24, false)) is PagingSource.LoadResult.Error) shouldBe true
    }

    @Test
    fun `endless empty continuation is bounded`() = runBlocking<Unit> {
        var requests = 0
        val paging = pager { requests++; MangasPage(emptyList(), true) }
        (paging.load(params()) is PagingSource.LoadResult.Error) shouldBe true
        requests shouldBe 9
    }

    @Test
    fun `cancelled catalogue does not become paging error`() = runBlocking<Unit> {
        val paging = pager { throw CancellationException("Cancelled") }
        assertThrows<CancellationException> { paging.load(params()) }
    }
}
