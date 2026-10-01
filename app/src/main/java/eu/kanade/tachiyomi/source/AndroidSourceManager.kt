package eu.kanade.tachiyomi.source

import android.content.Context
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
import logcat.logcat
import mihon.domain.source.registry.DefaultSourceCollisionPolicy
import mihon.domain.source.registry.InternalSourceRegistry
import mihon.domain.source.registry.SourceCollisionPolicy
import mihon.domain.source.registry.SourcePreferenceMode
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.repository.StubSourceRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.ConcurrentHashMap

class AndroidSourceManager(
    private val context: Context,
    private val extensionManager: ExtensionManager,
    private val sourceRepository: StubSourceRepository,
    private val internalSourceRegistry: InternalSourceRegistry = Injekt.get(),
    private val collisionPolicy: SourceCollisionPolicy = DefaultSourceCollisionPolicy(),
    private val scope: CoroutineScope = CoroutineScope(Job() + Dispatchers.IO),
) : SourceManager {

    private val _isInitialized = MutableStateFlow(false)
    override val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val downloadManager: DownloadManager by injectLazy()

    private val sourcesMapFlow = MutableStateFlow(ConcurrentHashMap<Long, Source>())

    private val stubSourcesMap = ConcurrentHashMap<Long, StubSource>()

    private val sourcePreferenceModes = ConcurrentHashMap<Long, SourcePreferenceMode>()

    override val sources: Flow<List<Source>> = sourcesMapFlow.map { it.values.toList() }

    init {
        scope.launch {
            extensionManager.installedExtensionsFlow
                .collectLatest { extensions ->
                    val newMap = buildCanonicalSourcesMap(extensions)
                    sourcesMapFlow.value = newMap
                    _isInitialized.value = true
                }
        }

        scope.launch {
            sourceRepository.subscribeAll()
                .collectLatest { sources ->
                    val mutableMap = stubSourcesMap.toMutableMap()
                    sources.forEach {
                        mutableMap[it.id] = it
                    }
                }
        }
    }

    fun setPreferenceMode(sourceId: Long, mode: SourcePreferenceMode) {
        sourcePreferenceModes[sourceId] = mode
        refreshSources()
    }

    fun refreshSources() {
        val currentExtensions = extensionManager.installedExtensionsFlow.value
        sourcesMapFlow.value = buildCanonicalSourcesMap(currentExtensions)
    }

    private fun buildCanonicalSourcesMap(extensions: List<Extension>): ConcurrentHashMap<Long, Source> {
        val resultMap = ConcurrentHashMap<Long, Source>()

        val localSource = LocalSource(context, Injekt.get(), Injekt.get())
        resultMap[LocalSource.ID] = localSource

        val internalSourcesMap = try {
            internalSourceRegistry.getSources().associateBy { it.id }
        } catch (_: Exception) {
            emptyMap()
        }

        val extensionSourcesMap = mutableMapOf<Long, Source>()
        extensions.filterIsInstance<Extension.Installed>().forEach { extension ->
            extension.sources.forEach { source ->
                extensionSourcesMap[source.id] = source
                registerStubSource(StubSource.from(source))
            }
        }

        internalSourcesMap.values.forEach { source ->
            registerStubSource(StubSource.from(source))
        }

        val allSourceIds = (internalSourcesMap.keys + extensionSourcesMap.keys).filter { it != LocalSource.ID }

        for (sourceId in allSourceIds) {
            val internalSrc = internalSourcesMap[sourceId]
            val extensionSrc = extensionSourcesMap[sourceId]
            val prefMode = sourcePreferenceModes[sourceId] ?: SourcePreferenceMode.EXTERNAL_PREFERRED

            val resolution = collisionPolicy.resolveCollision(
                sourceId = sourceId,
                internalSource = internalSrc,
                extensionSource = extensionSrc,
                preferenceMode = prefMode,
            )

            if (resolution.isCollision) {
                logcat(LogPriority.INFO) {
                    "Source collision resolved for ID $sourceId: selected ${resolution.selectedSource.javaClass.simpleName} (${resolution.selectedOrigin}), fallback ${resolution.fallbackSource?.javaClass?.simpleName} (${resolution.fallbackOrigin})"
                }
            }

            resultMap[sourceId] = resolution.selectedSource
        }

        return resultMap
    }

    override fun get(sourceKey: Long): Source? {
        return sourcesMapFlow.value[sourceKey]
    }

    override fun getOrStub(sourceKey: Long): Source {
        return sourcesMapFlow.value[sourceKey] ?: stubSourcesMap.getOrPut(sourceKey) {
            runBlocking { createStubSource(sourceKey) }
        }
    }

    override fun getAll() = sourcesMapFlow.value.values.toList()

    override fun getOnlineSources() = sourcesMapFlow.value.values.filterIsInstance<HttpSource>()

    override fun getStubSources(): List<StubSource> {
        val onlineSourceIds = getOnlineSources().map { it.id }
        return stubSourcesMap.values.filterNot { it.id in onlineSourceIds }
    }

    private fun registerStubSource(source: StubSource) {
        scope.launch {
            val dbSource = sourceRepository.getStubSource(source.id)
            if (dbSource == source) return@launch
            sourceRepository.upsertStubSource(source.id, source.lang, source.name)
            if (dbSource != null) {
                downloadManager.renameSource(dbSource, source)
            }
        }
    }

    private suspend fun createStubSource(id: Long): StubSource {
        sourceRepository.getStubSource(id)?.let {
            return it
        }
        extensionManager.getSourceData(id)?.let {
            registerStubSource(it)
            return it
        }
        return StubSource(id = id, lang = "", name = "")
    }
}
