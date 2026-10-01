package eu.kanade.tachiyomi.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.kanade.domain.source.interactor.GetEnabledSources
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
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
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.domain.updates.model.UpdatesWithRelations
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Calendar

class HomeViewModel(
    private val getHistory: GetHistory = Injekt.get(),
    private val getUpdates: GetUpdates = Injekt.get(),
    private val getLibraryManga: GetLibraryManga = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val getEnabledSources: GetEnabledSources = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
    private val getSourceCapabilities: GetSourceCapabilities = GetSourceCapabilities(),
    private val getSourceDiscovery: GetSourceDiscovery = GetSourceDiscovery(getSourceCapabilities),
) : ViewModel() {

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    private var featuredIndex = 0
    private var allDiscoveryItems = mutableListOf<HomeDiscoveryItem>()
    private val sourcePageMap = mutableMapOf<Long, Int>()
    private val hasMorePagesMap = mutableMapOf<Long, Boolean>()
    private var discoveryJob: Job? = null
    private var paginationJob: Job? = null

    init {
        viewModelScope.launch {
            var unavailable = emptySet<Long>()
            mihon.domain.source.health.SourceHealthMonitor.shared.states.collectLatest { health ->
                val hidden = health.filterValues { it.state == mihon.domain.source.health.SourceHealthMonitor.State.UNAVAILABLE }.keys
                _state.update { current -> current.copy(
                    discoveryFeatured = current.discoveryFeatured?.takeUnless { it.sourceId in hidden },
                    popularManga = current.popularManga.filterNot { it.sourceId in hidden },
                    latestManga = current.latestManga.filterNot { it.sourceId in hidden },
                    newManga = current.newManga.filterNot { it.sourceId in hidden },
                    completedManga = current.completedManga.filterNot { it.sourceId in hidden },
                    discoveryLatest = current.discoveryLatest.filterNot { it.sourceId in hidden },
                ) }
                allDiscoveryItems.removeAll { it.sourceId in hidden }
                val failed = health.filterValues { it.failures > 0 || it.state == mihon.domain.source.health.SourceHealthMonitor.State.UNAVAILABLE }.keys
                if ((unavailable - failed).isNotEmpty()) {
                    loadDiscoveryContent(_state.value.installedSources.mapNotNull { sourceManager.get(it.id) as? CatalogueSource }, isRefresh = true)
                }
                unavailable = failed
            }
        }
        // Collect history
        viewModelScope.launch {
            getHistory.subscribe("").collectLatest { history ->
                _state.update { it.copy(recentHistory = history.distinctBy { h -> h.mangaId }.take(6)) }
            }
        }

        // Collect updates
        viewModelScope.launch {
            val calendar = Calendar.getInstance()
            calendar.add(Calendar.MONTH, -3)
            getUpdates.subscribe(read = false, after = calendar.timeInMillis).collectLatest { updates ->
                _state.update { it.copy(recentUpdates = updates.distinctBy { u -> u.mangaId }.take(8)) }
            }
        }

        // Collect library manga
        viewModelScope.launch {
            getLibraryManga.subscribe().collectLatest { library ->
                _state.update { it.copy(libraryManga = library.distinctBy { l -> l.id }.take(10)) }
            }
        }

        // Collect active downloads
        viewModelScope.launch {
            downloadManager.queueState.collectLatest { queue ->
                _state.update { it.copy(activeDownloadsCount = queue.size) }
            }
        }

        // Collect enabled extension sources & fetch discovery catalog
        viewModelScope.launch {
            getEnabledSources.subscribe().collectLatest { sources ->
                val onlineSources = sources
                    .mapNotNull { sourceManager.get(it.id) as? CatalogueSource }
                    .filter { it is HttpSource }
                    .distinctBy { it.id }

                val sourceItems = onlineSources.map {
                    HomeSourceItem(id = it.id, name = it.name, lang = it.lang)
                }

                _state.update { it.copy(installedSources = sourceItems) }

                if (_state.value.popularManga.isEmpty() &&
                    _state.value.latestManga.isEmpty() &&
                    _state.value.completedManga.isEmpty() &&
                    _state.value.newManga.isEmpty()
                ) {
                    loadDiscoveryContent(onlineSources)
                }
            }
        }
    }

    private fun loadDiscoveryContent(sources: List<CatalogueSource>, isRefresh: Boolean = false) {
        val previousJob = discoveryJob
        previousJob?.cancel()
        paginationJob?.cancel()
        discoveryJob = viewModelScope.launch {
            previousJob?.cancelAndJoin()

            val hasExistingContent = _state.value.popularManga.isNotEmpty() ||
                _state.value.latestManga.isNotEmpty() ||
                _state.value.completedManga.isNotEmpty() ||
                _state.value.newManga.isNotEmpty()
            if (!hasExistingContent && !isRefresh) {
                _state.update { it.copy(isDiscoveryLoading = true, isPaginationLoading = false, discoveryError = null) }
            }

            val eligibleSources = sources.distinctBy { s -> s.id }
            if (eligibleSources.isEmpty()) {
                _state.update {
                    it.copy(
                        discoveryFeatured = null,
                        popularManga = emptyList(),
                        latestManga = emptyList(),
                        newManga = emptyList(),
                        completedManga = emptyList(),
                        discoveryLatest = emptyList(),
                        isDiscoveryLoading = false,
                    )
                }
                return@launch
            }

            val payload = fetchSourceBatch(eligibleSources)
            updateDiscoveryState(payload, isFinal = true)
        }
    }

    private suspend fun fetchSourceBatch(batchSources: List<CatalogueSource>): DiscoveryBatchResultPayload {
        return withContext(Dispatchers.IO) { kotlinx.coroutines.supervisorScope {
            val semaphore = Semaphore(2)

            val results = batchSources.map { source ->
                async {
                    try {
                        semaphore.withPermit {
                            val capabilities = getSourceCapabilities(source)
                            val categories = buildList {
                                add(DiscoveryCategory.POPULAR)
                                if (capabilities.supportsLatest == CapabilitySupport.SUPPORTED) add(DiscoveryCategory.LATEST)
                                if (capabilities.supportsCompletedFilter == CapabilitySupport.SUPPORTED) add(DiscoveryCategory.COMPLETED)
                                if (capabilities.supportsNewFilter == CapabilitySupport.SUPPORTED) add(DiscoveryCategory.NEW)
                            }
                            categories.map { category ->
                                getSourceDiscovery(source, category, page = 1).also {
                                    if (category == DiscoveryCategory.POPULAR) publishEarlyPopular(it)
                                }
                            }
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { emptyList() }
                }
            }.awaitAll().flatten()
            val popularResults = results.filter { it.category == DiscoveryCategory.POPULAR }
            val latestResults = results.filter { it.category == DiscoveryCategory.LATEST }
            val completedResults = results.filter { it.category == DiscoveryCategory.COMPLETED }
            val newResults = results.filter { it.category == DiscoveryCategory.NEW }

            val perSourcePopular = mutableListOf<List<HomeDiscoveryItem>>()
            val pageUpdates = mutableMapOf<Long, Int>()
            val hasMoreUpdates = mutableMapOf<Long, Boolean>()

            for (res in popularResults) {
                pageUpdates[res.sourceId] = res.page
                hasMoreUpdates[res.sourceId] = res.hasNextPage
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
                if (items.isNotEmpty()) perSourcePopular.add(items)
            }

            val perSourceLatest = mutableListOf<List<HomeDiscoveryItem>>()
            for (res in latestResults) {
                pageUpdates[res.sourceId] = res.page
                hasMoreUpdates[res.sourceId] = res.hasNextPage
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
                if (items.isNotEmpty()) perSourceLatest.add(items)
            }

            val perSourceCompleted = mutableListOf<List<HomeDiscoveryItem>>()
            for (res in completedResults) {
                pageUpdates[res.sourceId] = res.page
                hasMoreUpdates[res.sourceId] = res.hasNextPage
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
                if (items.isNotEmpty()) perSourceCompleted.add(items)
            }

            val perSourceNew = mutableListOf<List<HomeDiscoveryItem>>()
            for (res in newResults) {
                pageUpdates[res.sourceId] = res.page
                hasMoreUpdates[res.sourceId] = res.hasNextPage
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
                if (items.isNotEmpty()) perSourceNew.add(items)
            }

            val interleavedPopular = interleaveSources(perSourcePopular).distinctBy { "${it.sourceId}_${it.mangaId}" }
            val interleavedLatest = interleaveSources(perSourceLatest).distinctBy { "${it.sourceId}_${it.mangaId}" }
            val interleavedNew = interleaveSources(perSourceNew).distinctBy { "${it.sourceId}_${it.mangaId}" }
            val interleavedCompleted = interleaveSources(perSourceCompleted).distinctBy { "${it.sourceId}_${it.mangaId}" }

            DiscoveryBatchResultPayload(
                pageMapUpdates = pageUpdates,
                hasMoreMapUpdates = hasMoreUpdates,
                popularItems = interleavedPopular,
                latestItems = interleavedLatest,
                newItems = interleavedNew,
                completedItems = interleavedCompleted,
            )
        } }
    }

    /** Publish each healthy source as soon as it finishes, independent of a hung sibling. */
    private suspend fun publishEarlyPopular(result: mihon.domain.source.discovery.model.SourceDiscoveryResult) {
        if (result.items.isEmpty() || !mihon.domain.source.health.SourceHealthMonitor.shared.discoverable(result.sourceId)) return
        val local = networkToLocalManga(result.items.map { it.toDomainManga() })
        val incoming = local.map { manga ->
            HomeDiscoveryItem(mangaId = manga.id, title = manga.title, coverData = manga.asMangaCover(),
                sourceId = result.sourceId, sourceName = result.sourceName, url = manga.url)
        }
        _state.update { current ->
            val combined = (current.popularManga + listOfNotNull(current.discoveryFeatured) + incoming).distinctBy { "${it.sourceId}_${it.mangaId}" }
            val featured = current.discoveryFeatured ?: combined.firstOrNull()
            val popular = combined.filterNot { it.sourceId == featured?.sourceId && it.mangaId == featured.mangaId }
            current.copy(discoveryFeatured = featured, popularManga = popular, discoveryLatest = popular, isDiscoveryLoading = false)
        }
    }

    private fun updateDiscoveryState(batchResult: DiscoveryBatchResultPayload, isFinal: Boolean) {
        sourcePageMap.putAll(batchResult.pageMapUpdates)
        hasMorePagesMap.putAll(batchResult.hasMoreMapUpdates)
        allDiscoveryItems = batchResult.popularItems.toMutableList()

        fun preserve(incoming: List<HomeDiscoveryItem>, previous: List<HomeDiscoveryItem>): List<HomeDiscoveryItem> {
            val health = mihon.domain.source.health.SourceHealthMonitor.shared
            val present = incoming.map { it.sourceId }.toSet()
            return (incoming + previous.filter { it.sourceId !in present && health.health(it.sourceId).state == mihon.domain.source.health.SourceHealthMonitor.State.DEGRADED })
                .filter { health.discoverable(it.sourceId) }.distinctBy { "${it.sourceId}_${it.mangaId}" }
        }
        val old = _state.value
        val popularList = preserve(batchResult.popularItems, old.popularManga + listOfNotNull(old.discoveryFeatured))
        val latestList = preserve(batchResult.latestItems, old.latestManga)
        val newList = preserve(batchResult.newItems, old.newManga)
        val completedList = preserve(batchResult.completedItems, old.completedManga)

        if (popularList.isNotEmpty()) {
            val savedMangaId = sourcePreferences.featuredMangaId.get()
            featuredIndex = selectFeaturedIndex(popularList.map { it.mangaId }, savedMangaId, featuredIndex)
            val featured = popularList[featuredIndex]
            sourcePreferences.featuredMangaId.set(featured.mangaId)
            val popularFiltered = popularList.filterIndexed { index, _ -> index != featuredIndex }

            _state.update {
                it.copy(
                    discoveryFeatured = featured,
                    popularManga = popularFiltered,
                    latestManga = latestList,
                    newManga = newList,
                    completedManga = completedList,
                    discoveryLatest = popularFiltered,
                    isDiscoveryLoading = !isFinal,
                )
            }
        } else {
            _state.update {
                it.copy(
                    popularManga = emptyList(),
                    latestManga = latestList,
                    newManga = newList,
                    completedManga = completedList,
                    discoveryLatest = emptyList(),
                    isDiscoveryLoading = !isFinal,
                )
            }
        }
    }

    fun loadNextPage() {
        if (_state.value.isPaginationLoading || _state.value.isDiscoveryLoading || discoveryJob?.isActive == true) return

        paginationJob = viewModelScope.launch {
            _state.update { it.copy(isPaginationLoading = true) }
            val eligibleSources = _state.value.installedSources.mapNotNull {
                sourceManager.get(it.id) as? CatalogueSource
            }.filter { hasMorePagesMap[it.id] != false }
            val requestedPages = eligibleSources.associate { it.id to ((sourcePageMap[it.id] ?: 1) + 1) }

            val pageResults = withContext(Dispatchers.IO) {
                eligibleSources.map { source ->
                    val nextPage = requestedPages.getValue(source.id)
                    val mangasPage: MangasPage? = try {
                        mihon.domain.source.health.SourceHealthMonitor.shared.run(source.id) {
                            if (source.supportsLatest) source.getLatestUpdates(nextPage) else source.getPopularManga(nextPage)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    if (mangasPage == null) {
                        PaginationResult(source.id, nextPage, false, emptyList())
                    } else {
                        val domainMangas = mangasPage.mangas.map { sManga ->
                            sManga.toDomainManga(source.id)
                        }
                        val localMangas = networkToLocalManga(domainMangas)
                        PaginationResult(source.id, nextPage, mangasPage.hasNextPage, localMangas.map { manga ->
                            HomeDiscoveryItem(
                                mangaId = manga.id,
                                title = manga.title,
                                coverData = manga.asMangaCover(),
                                sourceId = source.id,
                                sourceName = source.name,
                            )
                        })
                    }
                }
            }
            val newItems = pageResults.flatMap { result ->
                if (result.items.isNotEmpty() || result.hasNextPage) {
                    sourcePageMap[result.sourceId] = result.page
                }
                hasMorePagesMap[result.sourceId] = result.hasNextPage
                result.items
            }

            if (newItems.isNotEmpty()) {
                allDiscoveryItems.addAll(newItems)
                val snapshot = allDiscoveryItems.toList()
                val deduplicated = withContext(Dispatchers.Default) {
                    deduplicateAndUnify(snapshot)
                }
                val latest = deduplicated.filter { it.mangaId != _state.value.discoveryFeatured?.mangaId }
                _state.update {
                    it.copy(
                        discoveryLatest = latest,
                        isPaginationLoading = false,
                    )
                }
            } else {
                _state.update { it.copy(isPaginationLoading = false) }
            }
        }
    }

    fun nextFeaturedStory() {
        val deduplicated = deduplicateAndUnify(allDiscoveryItems)
        if (deduplicated.size > 1) {
            featuredIndex = (featuredIndex + 1) % deduplicated.size
            val featured = deduplicated[featuredIndex]
            sourcePreferences.featuredMangaId.set(featured.mangaId)
            val latest = deduplicated.filterIndexed { index, _ -> index != featuredIndex }
            _state.update {
                it.copy(
                    discoveryFeatured = featured,
                    discoveryLatest = latest,
                )
            }
        }
    }

    fun onHomeSwipeRefresh() {
        viewModelScope.launch {
            _state.update { it.copy(isSwipeRefreshing = true) }
            nextFeaturedStory()
            delay(250)
            _state.update { it.copy(isSwipeRefreshing = false) }
        }
    }

    fun refreshDiscovery() {
        val onlineSources = _state.value.installedSources.mapNotNull {
            sourceManager.get(it.id) as? CatalogueSource
        }.distinctBy { s -> s.id }
        if (onlineSources.isNotEmpty()) {
            loadDiscoveryContent(onlineSources)
        }
    }

    private fun deduplicateAndUnify(items: List<HomeDiscoveryItem>): List<HomeDiscoveryItem> {
        return GroupDiscoveryItems.group(items)
    }
}

internal fun selectFeaturedIndex(mangaIds: List<Long>, savedMangaId: Long, currentIndex: Int): Int {
    require(mangaIds.isNotEmpty())
    val savedIndex = mangaIds.indexOf(savedMangaId)
    return if (savedIndex >= 0) savedIndex else currentIndex.mod(mangaIds.size)
}

internal fun <T> interleaveSources(sourceLists: List<List<T>>): List<T> {
    val result = mutableListOf<T>()
    var index = 0
    while (true) {
        var added = false
        for (list in sourceLists) {
            if (index < list.size) {
                result.add(list[index])
                added = true
            }
        }
        if (!added) break
        index++
    }
    return result
}

data class HomeSourceVersion(
    val mangaId: Long,
    val sourceId: Long,
    val sourceName: String,
    val url: String = "",
    val title: String = "",
)

private data class SourceFetchResult(
    val sourceId: Long,
    val sourceName: String,
    val page: Int,
    val hasNextPage: Boolean,
    val mangas: List<Manga>,
)

private data class PaginationResult(
    val sourceId: Long,
    val page: Int,
    val hasNextPage: Boolean,
    val items: List<HomeDiscoveryItem>,
)

private data class DiscoveryBatchResultPayload(
    val pageMapUpdates: Map<Long, Int>,
    val hasMoreMapUpdates: Map<Long, Boolean>,
    val popularItems: List<HomeDiscoveryItem>,
    val latestItems: List<HomeDiscoveryItem>,
    val newItems: List<HomeDiscoveryItem>,
    val completedItems: List<HomeDiscoveryItem>,
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

data class HomeDiscoveryItem(
    val mangaId: Long,
    val title: String,
    val coverData: MangaCover,
    val sourceId: Long,
    val sourceName: String,
    val url: String = "",
    val availableVersions: List<HomeSourceVersion> = emptyList(),
)

data class HomeSourceItem(
    val id: Long,
    val name: String,
    val lang: String,
)

data class HomeState(
    val recentHistory: List<HistoryWithRelations> = emptyList(),
    val recentUpdates: List<UpdatesWithRelations> = emptyList(),
    val libraryManga: List<LibraryManga> = emptyList(),
    val activeDownloadsCount: Int = 0,
    val discoveryFeatured: HomeDiscoveryItem? = null,
    val popularManga: List<HomeDiscoveryItem> = emptyList(),
    val latestManga: List<HomeDiscoveryItem> = emptyList(),
    val newManga: List<HomeDiscoveryItem> = emptyList(),
    val completedManga: List<HomeDiscoveryItem> = emptyList(),
    val discoveryLatest: List<HomeDiscoveryItem> = emptyList(),
    val installedSources: List<HomeSourceItem> = emptyList(),
    val isDiscoveryLoading: Boolean = false,
    val isSwipeRefreshing: Boolean = false,
    val isPaginationLoading: Boolean = false,
    val discoveryError: String? = null,
)
