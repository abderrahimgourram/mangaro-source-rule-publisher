package eu.kanade.tachiyomi.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import eu.kanade.domain.source.interactor.GetEnabledSources
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import mihon.domain.manga.model.toDomainManga
import mihon.domain.source.discovery.interactor.GetSourceCapabilities
import mihon.domain.source.discovery.interactor.GetSourceDiscovery
import mihon.domain.source.discovery.model.CapabilitySupport
import mihon.domain.source.discovery.model.DiscoveryCategory
import mihon.domain.source.discovery.model.SourceDiscoveryItem
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class DiscoveryCategoryGridViewModel(
    val category: DiscoveryCategory,
    private val getEnabledSources: GetEnabledSources = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val getSourceCapabilities: GetSourceCapabilities = GetSourceCapabilities(),
    private val getSourceDiscovery: GetSourceDiscovery = GetSourceDiscovery(getSourceCapabilities),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
) : ViewModel() {

    data class State(
        val items: List<HomeDiscoveryItem> = emptyList(),
        val isLoadingInitial: Boolean = true,
        val isPaginationLoading: Boolean = false,
        val isSwipeRefreshing: Boolean = false,
        val error: String? = null,
        val hasMore: Boolean = true,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val sourcePageMap = mutableMapOf<Long, Int>()
    private val sourceHasMoreMap = mutableMapOf<Long, Boolean>()
    private var sourcesList = emptyList<CatalogueSource>()

    private var initialJob: Job? = null
    private var paginationJob: Job? = null

    init {
        viewModelScope.launch {
            var failed = emptySet<Long>()
            mihon.domain.source.health.SourceHealthMonitor.shared.states.collectLatest { states ->
                _state.update { current -> current.copy(items = current.items.filter { item -> mihon.domain.source.health.SourceHealthMonitor.shared.discoverable(item.sourceId) }) }
                val nowFailed = states.filterValues { it.failures > 0 || it.state == mihon.domain.source.health.SourceHealthMonitor.State.UNAVAILABLE }.keys
                if ((failed - nowFailed).isNotEmpty() && sourcesList.isNotEmpty()) loadInitialPage(isRefresh = true, hasSeedItems = _state.value.items.isNotEmpty())
                failed = nowFailed
            }
        }
        val seedItems = DiscoverySnapshotStore.getSnapshot(category)
        if (seedItems.isNotEmpty()) {
            _state.update {
                it.copy(
                    items = seedItems,
                    isLoadingInitial = false,
                    hasMore = true,
                )
            }
        }

        viewModelScope.launch {
            getEnabledSources.subscribe().collectLatest { sources ->
                val onlineSources = sources
                    .mapNotNull { sourceManager.get(it.id) as? CatalogueSource }
                    .filter { it is HttpSource }
                    .distinctBy { it.id }

                val eligibleSources = filterEligibleSources(onlineSources, category)
                sourcesList = eligibleSources

                loadInitialPage(isRefresh = false, hasSeedItems = seedItems.isNotEmpty())
            }
        }
    }

    private fun filterEligibleSources(sources: List<CatalogueSource>, category: DiscoveryCategory): List<CatalogueSource> {
        return sources.filter { source ->
            val caps = getSourceCapabilities(source)
            when (category) {
                DiscoveryCategory.POPULAR -> caps.supportsPopular == CapabilitySupport.SUPPORTED
                DiscoveryCategory.LATEST -> caps.supportsLatest == CapabilitySupport.SUPPORTED
                DiscoveryCategory.NEW -> caps.supportsNewFilter == CapabilitySupport.SUPPORTED
                DiscoveryCategory.COMPLETED -> caps.supportsCompletedFilter == CapabilitySupport.SUPPORTED
            }
        }
    }

    fun loadInitialPage(isRefresh: Boolean = false, hasSeedItems: Boolean = false) {
        val previousJob = initialJob
        previousJob?.cancel()
        paginationJob?.cancel()
        initialJob = viewModelScope.launch {
            previousJob?.cancelAndJoin()

            if (!isRefresh && _state.value.items.isEmpty() && !hasSeedItems) {
                _state.update { it.copy(isLoadingInitial = true, error = null) }
            } else if (isRefresh) {
                _state.update { it.copy(isSwipeRefreshing = true, error = null) }
            }

            val pageMapToUse = if (!isRefresh && hasSeedItems) sourcePageMap else emptyMap()
            val batchResult = fetchCategoryPage(sourcesList, pageMap = pageMapToUse)

            sourcePageMap.putAll(batchResult.pageMap)
            sourceHasMoreMap.putAll(batchResult.hasMoreMap)

            val currentItems = if (isRefresh) _state.value.items.filter {
                mihon.domain.source.health.SourceHealthMonitor.shared.health(it.sourceId).state == mihon.domain.source.health.SourceHealthMonitor.State.DEGRADED
            } else _state.value.items
            val combinedItems = GroupDiscoveryItems.group(currentItems + batchResult.items).filter { mihon.domain.source.health.SourceHealthMonitor.shared.discoverable(it.sourceId) }
            val hasMoreAny = sourceHasMoreMap.values.any { it }

            _state.update {
                it.copy(
                    items = combinedItems,
                    isLoadingInitial = false,
                    isSwipeRefreshing = false,
                    hasMore = hasMoreAny,
                    error = if (combinedItems.isEmpty() && sourcesList.isNotEmpty()) "لا توجد نتائج" else null,
                )
            }
        }
    }

    fun loadNextPage() {
        if (_state.value.isPaginationLoading || _state.value.isLoadingInitial || !_state.value.hasMore) return

        val paginationSources = sourcesList.filter { sourceHasMoreMap[it.id] != false }
        if (paginationSources.isEmpty()) {
            _state.update { it.copy(hasMore = false) }
            return
        }

        paginationJob = viewModelScope.launch {
            _state.update { it.copy(isPaginationLoading = true) }

            val batchResult = fetchCategoryPage(paginationSources, pageMap = sourcePageMap)

            sourcePageMap.putAll(batchResult.pageMap)
            sourceHasMoreMap.putAll(batchResult.hasMoreMap)

            val newCombined = GroupDiscoveryItems.group(_state.value.items + batchResult.items)
            val hasMoreAny = sourceHasMoreMap.values.any { it }

            _state.update {
                it.copy(
                    items = newCombined,
                    isPaginationLoading = false,
                    hasMore = hasMoreAny,
                )
            }
        }
    }

    private suspend fun fetchCategoryPage(
        sources: List<CatalogueSource>,
        pageMap: Map<Long, Int>,
    ): CategoryBatchResult {
        return withContext(Dispatchers.IO) {
            val semaphore = Semaphore(2)

            val fetchDeferreds = sources.map { source ->
                val currentPage = pageMap[source.id] ?: 0
                val nextPage = currentPage + 1
                async {
                    semaphore.withPermit {
                        getSourceDiscovery(source, category, page = nextPage)
                    }
                }
            }

            val fetchResults = fetchDeferreds.awaitAll()

            val perSourceItems = mutableListOf<List<HomeDiscoveryItem>>()
            val newPageMap = mutableMapOf<Long, Int>()
            val newHasMoreMap = mutableMapOf<Long, Boolean>()

            for (res in fetchResults) {
                newPageMap[res.sourceId] = res.page
                newHasMoreMap[res.sourceId] = res.hasNextPage

                if (res.items.isEmpty()) continue

                val domainMangas = res.items.take(12).map { it.toDomainManga() }
                val localMangas = networkToLocalManga(domainMangas)
                val items = localMangas.mapIndexed { idx, manga ->
                    HomeDiscoveryItem(
                        mangaId = manga.id,
                        title = manga.title,
                        coverData = manga.asMangaCover(),
                        sourceId = res.sourceId,
                        sourceName = res.sourceName,
                        url = res.items.getOrNull(idx)?.url ?: manga.url,
                    )
                }
                if (items.isNotEmpty()) perSourceItems.add(items)
            }

            val interleaved = GroupDiscoveryItems.group(interleaveSources(perSourceItems))

            CategoryBatchResult(
                pageMap = newPageMap,
                hasMoreMap = newHasMoreMap,
                items = interleaved,
            )
        }
    }

    private data class CategoryBatchResult(
        val pageMap: Map<Long, Int>,
        val hasMoreMap: Map<Long, Boolean>,
        val items: List<HomeDiscoveryItem>,
    )

    private fun SourceDiscoveryItem.toDomainManga(): Manga {
        return Manga.create().copy(
            url = url,
            title = title,
            artist = artist,
            author = author,
            description = description,
            genre = genre.ifEmpty { null },
            status = status.toLong(),
            thumbnailUrl = thumbnailUrl,
            initialized = false,
            source = sourceId,
        )
    }

    class Factory(private val category: DiscoveryCategory) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DiscoveryCategoryGridViewModel(category) as T
        }
    }
}
