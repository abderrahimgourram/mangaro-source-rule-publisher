package mihon.domain.source.discovery.interactor

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.domain.source.discovery.model.CapabilitySupport
import mihon.domain.source.discovery.model.DiscoveryCategory
import mihon.domain.source.discovery.model.SourceDiscoveryItem
import mihon.domain.source.discovery.model.SourceDiscoveryResult

class GetSourceDiscovery(
    private val getSourceCapabilities: GetSourceCapabilities = GetSourceCapabilities(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val health: mihon.domain.source.health.SourceHealthMonitor = mihon.domain.source.health.SourceHealthMonitor.shared,
) {

    private val cached = if (health === mihon.domain.source.health.SourceHealthMonitor.shared) sharedSnapshots else java.util.concurrent.ConcurrentHashMap<String, SourceDiscoveryResult>()
    companion object { private val sharedSnapshots = java.util.concurrent.ConcurrentHashMap<String, SourceDiscoveryResult>() }

    suspend operator fun invoke(
        source: CatalogueSource,
        category: DiscoveryCategory,
        page: Int = 1,
    ): SourceDiscoveryResult = withContext(ioDispatcher) {
        val capabilities = getSourceCapabilities(source)
        val cacheKey = "${source.id}:$category:$page"

        val supported = when (category) {
            DiscoveryCategory.POPULAR -> true
            DiscoveryCategory.LATEST -> capabilities.supportsLatest == CapabilitySupport.SUPPORTED
            DiscoveryCategory.COMPLETED -> capabilities.supportsCompletedFilter == CapabilitySupport.SUPPORTED
            DiscoveryCategory.NEW -> capabilities.supportsNewFilter == CapabilitySupport.SUPPORTED
        }
        if (!supported) return@withContext SourceDiscoveryResult(source.id, source.name, category, page, false, emptyList())
        val mangasPage: MangasPage = try {
            health.run(source.id) {
                when (category) {
                    DiscoveryCategory.POPULAR -> source.getPopularManga(page)
                    DiscoveryCategory.LATEST -> source.getLatestUpdates(page)
                    DiscoveryCategory.COMPLETED -> source.getSearchManga(page, "", prepareCompletedFilter(source))
                    DiscoveryCategory.NEW -> source.getSearchManga(page, "", prepareNewFilter(source))
                }.also { result ->
                    if (result.mangas.any { it.url.isBlank() || it.title.isBlank() }) throw java.io.IOException("Source returned invalid catalogue identities")
                    if (category == DiscoveryCategory.POPULAR && page == 1) {
                        if (result.mangas.isNotEmpty()) health.expectCatalogue(source.id)
                        else if (!result.hasNextPage && (health.expectsCatalogue(source.id) || cached[cacheKey]?.items?.isNotEmpty() == true)) {
                            throw java.io.IOException("Source returned an impossible empty popular catalogue")
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (health.discoverable(source.id)) cached[cacheKey]?.let { return@withContext it }
            return@withContext SourceDiscoveryResult(
                sourceId = source.id,
                sourceName = source.name,
                category = category,
                page = page,
                hasNextPage = false,
                items = emptyList(),
            )
        }

        val items = mangasPage.mangas.map { sManga ->
            sManga.toSourceDiscoveryItem(source.id, source.name)
        }

        SourceDiscoveryResult(
            sourceId = source.id,
            sourceName = source.name,
            category = category,
            page = page,
            hasNextPage = mangasPage.hasNextPage,
            items = items,
        ).also {
            // Only bounded first-page discovery snapshots are cached, never error/empty placeholders.
            if (page == 1 && items.isNotEmpty()) {
                if (cached.size >= 128) cached.clear()
                cached[cacheKey] = it
            }
        }
    }

    private fun prepareCompletedFilter(source: CatalogueSource): FilterList {
        val filters = try {
            source.getFilterList()
        } catch (_: Exception) {
            return FilterList()
        }

        fun applyFilter(filter: Filter<*>): Boolean {
            if (filter is Filter.Select<*>) {
                val index = filter.values.indexOfFirst { value ->
                    val text = value.toString().lowercase()
                    text.contains("completed") ||
                        text.contains("مكتمل") ||
                        text.contains("مكتملة") ||
                        text.contains("finish")
                }
                if (index >= 0) {
                    filter.state = index
                    return true
                }
            } else if (filter is Filter.Group<*>) {
                for (item in filter.state) {
                    if (item is Filter<*> && applyFilter(item)) {
                        return true
                    }
                }
            }
            return false
        }

        for (filter in filters) {
            if (applyFilter(filter)) break
        }

        return filters
    }

    private fun prepareNewFilter(source: CatalogueSource): FilterList {
        val filters = try {
            source.getFilterList()
        } catch (_: Exception) {
            return FilterList()
        }

        fun applyFilter(filter: Filter<*>): Boolean {
            if (filter is Filter.Select<*>) {
                val index = filter.values.indexOfFirst { value ->
                    val text = value.toString().lowercase()
                    isNewTitleValue(text)
                }
                if (index >= 0) {
                    filter.state = index
                    return true
                }
            } else if (filter is Filter.Group<*>) {
                for (item in filter.state) {
                    if (item is Filter<*> && applyFilter(item)) {
                        return true
                    }
                }
            }
            return false
        }

        for (filter in filters) {
            if (applyFilter(filter)) break
        }

        return filters
    }

    private fun isNewTitleValue(text: String): Boolean {
        val isAddedOrCreated = text.contains("date added") ||
            text.contains("added") ||
            text.contains("newly added") ||
            text.contains("new additions") ||
            text.contains("الأحدث إضافة") ||
            text.contains("تاريخ الإضافة") ||
            text == "new" ||
            text == "newest" ||
            text == "جديد" ||
            text.contains("أحدث المانجا")

        val isChapterUpdate = text.contains("latest chapter") ||
            text.contains("أحدث الفصول") ||
            text.contains("latest update") ||
            text.contains("updated")

        return isAddedOrCreated && !isChapterUpdate
    }

    private fun SManga.toSourceDiscoveryItem(sourceId: Long, sourceName: String): SourceDiscoveryItem {
        return SourceDiscoveryItem(
            sourceId = sourceId,
            sourceName = sourceName,
            url = url,
            title = title,
            thumbnailUrl = thumbnail_url,
            artist = artist,
            author = author,
            description = description,
            status = status,
            genre = getGenres() ?: emptyList(),
            localMangaId = null,
        )
    }
}
