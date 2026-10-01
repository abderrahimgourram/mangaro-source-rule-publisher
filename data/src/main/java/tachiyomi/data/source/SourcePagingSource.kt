package tachiyomi.data.source

import androidx.paging.PagingState
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import kotlinx.coroutines.CancellationException
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.repository.SourcePagingSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException

class SourceSearchPagingSource(
    source: Source,
    private val query: String,
    private val filters: FilterList,
) : BaseSourcePagingSource(source) {
    override suspend fun requestNextPage(currentPage: Int): MangasPage {
        return source.getSearchManga(currentPage, query, filters)
    }
}

class SourcePopularPagingSource(source: Source) : BaseSourcePagingSource(source) {
    override suspend fun requestNextPage(currentPage: Int): MangasPage {
        return source.getPopularManga(currentPage)
    }
}

class SourceLatestPagingSource(source: Source) : BaseSourcePagingSource(source) {
    override suspend fun requestNextPage(currentPage: Int): MangasPage {
        return source.getLatestUpdates(currentPage)
    }
}

abstract class BaseSourcePagingSource(
    protected val source: Source,
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val health: mihon.domain.source.health.SourceHealthMonitor = mihon.domain.source.health.SourceHealthMonitor.shared,
) : SourcePagingSource() {

    private val seenManga = hashSetOf<String>()

    abstract suspend fun requestNextPage(currentPage: Int): MangasPage

    override suspend fun load(params: LoadParams<Long>): LoadResult<Long, Manga> {
        val page = params.key ?: 1
        if (params is LoadParams.Refresh && page == 1L) seenManga.clear()

        return try {
            var currentPage = page.toInt()
            val mangasPage = health.run(source.id, 120_000) {
                withIOContext {
                    var result = requestNextPage(currentPage)
                    // Sources can filter unsupported content (for example novels) from a page.
                    var skipped = 0
                    while (result.mangas.isEmpty() && result.hasNextPage) {
                        if (++skipped > 8) throw IOException("Source returned too many empty catalogue pages")
                        result = requestNextPage(++currentPage)
                    }
                    if (result.mangas.isNotEmpty() && result.mangas.all { it.url in seenManga }) throw IOException("Source repeated a catalogue page")
                    result
                }
            }
            if (mangasPage.mangas.isEmpty() && page == 1L) throw NoResultsException()
            if (mangasPage.mangas.isNotEmpty() && mangasPage.mangas.all { it.url in seenManga }) {
                throw IOException("Source repeated a catalogue page")
            }

            val manga = mangasPage.mangas
                .map { it.toDomainManga(source.id) }
                .filter { it.url !in seenManga }
                .distinctBy { it.url }
                .let { networkToLocalManga(it) }
            seenManga.addAll(manga.map { it.url })

            LoadResult.Page(
                data = manga,
                prevKey = null,
                nextKey = if (mangasPage.hasNextPage) currentPage.toLong() + 1 else null,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Long, Manga>): Long? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey ?: anchorPage?.nextKey
        }
    }
}

class NoResultsException : Exception()
