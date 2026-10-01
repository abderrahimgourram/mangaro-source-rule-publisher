package eu.kanade.tachiyomi.source

import android.content.Context
import eu.kanade.domain.source.interactor.GetEnabledSources
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.internal.teamx.TeamX
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import mihon.domain.source.registry.DefaultInternalSourceRegistry
import mihon.domain.source.registry.DefaultSourceCollisionPolicy
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.data.source.SourceRepositoryImpl
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.repository.StubSourceRepository
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.io.LocalSourceFileSystem
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.fullType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TeamXFallbackAvailabilityTest {

    private val teamXId = 4110737012647435874L
    private val internalTeamX = TeamX()

    private lateinit var mockContext: Context
    private lateinit var mockExtensionManager: ExtensionManager
    private lateinit var fakeStubRepository: FakeStubSourceRepository
    private lateinit var sourcePreferences: SourcePreferences
    private lateinit var installedExtensionsFlow: MutableStateFlow<List<Extension.Installed>>

    @BeforeEach
    fun setUp() {
        mockContext = mockk(relaxed = true)
        mockExtensionManager = mockk(relaxed = true)
        fakeStubRepository = FakeStubSourceRepository()
        sourcePreferences = SourcePreferences(InMemoryPreferenceStore())

        Injekt.addSingletonFactory(fullType<LocalSourceFileSystem>()) { mockk<LocalSourceFileSystem>(relaxed = true) }
        Injekt.addSingletonFactory(fullType<LocalCoverManager>()) { mockk<LocalCoverManager>(relaxed = true) }
        Injekt.addSingletonFactory(fullType<DownloadManager>()) { mockk<DownloadManager>(relaxed = true) }

        // Default: Enable Arabic language
        sourcePreferences.enabledLanguages.set(setOf("ar"))

        installedExtensionsFlow = MutableStateFlow(emptyList())
        every { mockExtensionManager.installedExtensionsFlow } returns installedExtensionsFlow
        every { mockExtensionManager.getSourceData(any()) } returns null
    }

    private suspend fun TestScope.createSourceManager(): AndroidSourceManager {
        val internalRegistry = DefaultInternalSourceRegistry(listOf(internalTeamX))
        val sm = AndroidSourceManager(
            context = mockContext,
            extensionManager = mockExtensionManager,
            sourceRepository = fakeStubRepository,
            internalSourceRegistry = internalRegistry,
            collisionPolicy = DefaultSourceCollisionPolicy(),
            scope = backgroundScope,
        )
        sm.isInitialized.first { it }
        return sm
    }

    @Test
    fun `verify Arabic enabled with both external and internal provider resolves external as canonical`() = runTest {
        val externalTeamX = TestCatalogueSource(id = teamXId, name = "External Team X", lang = "ar")
        val extension = createInstalledExtension("eu.kanade.tachiyomi.extension.ar.teamx", listOf(externalTeamX))

        installedExtensionsFlow.value = listOf(extension)
        val sourceManager = createSourceManager()

        val resolvedSource = sourceManager.get(teamXId)
        resolvedSource shouldNotBe null
        resolvedSource!!.name shouldBe "External Team X"
        (resolvedSource is TeamX) shouldBe false
    }

    @Test
    fun `verify external provider removal automatically resolves internal TeamX as canonical`() = runTest {
        val externalTeamX = TestCatalogueSource(id = teamXId, name = "External Team X", lang = "ar")
        val extension = createInstalledExtension("eu.kanade.tachiyomi.extension.ar.teamx", listOf(externalTeamX))

        // Step 1: External active
        installedExtensionsFlow.value = listOf(extension)
        val sourceManager = createSourceManager()
        sourceManager.get(teamXId)!!.name shouldBe "External Team X"

        // Step 2: External removed / disabled
        installedExtensionsFlow.value = emptyList()
        sourceManager.sources.first { list -> list.any { it.id == teamXId && it.name == "Team X" } }

        val fallbackSource = sourceManager.get(teamXId)
        fallbackSource shouldNotBe null
        fallbackSource!!.name shouldBe "Team X"
        (fallbackSource is TeamX) shouldBe true
    }

    @Test
    fun `verify existing TeamX source ID remains resolvable and is not a StubSource`() = runTest {
        val sourceManager = createSourceManager()

        val resolved = sourceManager.getOrStub(teamXId)
        resolved shouldNotBe null
        resolved.name shouldBe "Team X"
        (resolved is StubSource) shouldBe false
        (resolved is TeamX) shouldBe true
    }

    @Test
    fun `verify GetEnabledSources still contains TeamX after external provider removal`() = runTest {
        val externalTeamX = TestCatalogueSource(id = teamXId, name = "External Team X", lang = "ar")
        val extension = createInstalledExtension("eu.kanade.tachiyomi.extension.ar.teamx", listOf(externalTeamX))

        installedExtensionsFlow.value = listOf(extension)
        val sourceManager = createSourceManager()
        val repository = SourceRepositoryImpl(sourceManager, mockk(relaxed = true))
        val getEnabledSources = GetEnabledSources(repository, sourcePreferences)

        // Step 1: External active -> GetEnabledSources contains External Team X
        var enabledList = getEnabledSources.subscribe().first { list -> list.any { it.id == teamXId && it.name == "External Team X" } }
        enabledList.any { it.id == teamXId } shouldBe true

        // Step 2: External removed -> GetEnabledSources STILL contains Team X (Internal)
        installedExtensionsFlow.value = emptyList()

        enabledList = getEnabledSources.subscribe().first { list -> list.any { it.id == teamXId && it.name == "Team X" } }
        enabledList.any { it.id == teamXId } shouldBe true
        enabledList.first { it.id == teamXId }.name shouldBe "Team X"
    }

    @Test
    fun `verify external provider restoration restores external as canonical again`() = runTest {
        val externalTeamX = TestCatalogueSource(id = teamXId, name = "External Team X", lang = "ar")
        val extension = createInstalledExtension("eu.kanade.tachiyomi.extension.ar.teamx", listOf(externalTeamX))

        val sourceManager = createSourceManager()

        // 1. Initial state (Internal only)
        installedExtensionsFlow.value = emptyList()
        sourceManager.sources.first { list -> list.any { it.id == teamXId && it.name == "Team X" } }
        sourceManager.get(teamXId)!!.name shouldBe "Team X"

        // 2. Extension added -> External canonical
        installedExtensionsFlow.value = listOf(extension)
        sourceManager.sources.first { list -> list.any { it.id == teamXId && it.name == "External Team X" } }
        sourceManager.get(teamXId)!!.name shouldBe "External Team X"

        // 3. Extension removed -> Internal canonical again
        installedExtensionsFlow.value = emptyList()
        sourceManager.sources.first { list -> list.any { it.id == teamXId && it.name == "Team X" } }
        sourceManager.get(teamXId)!!.name shouldBe "Team X"

        // 4. Extension restored -> External canonical again
        installedExtensionsFlow.value = listOf(extension)
        sourceManager.sources.first { list -> list.any { it.id == teamXId && it.name == "External Team X" } }
        sourceManager.get(teamXId)!!.name shouldBe "External Team X"
    }

    @Test
    fun `verify repeated provider transitions produce no duplicate source IDs`() = runTest {
        val externalTeamX = TestCatalogueSource(id = teamXId, name = "External Team X", lang = "ar")
        val extension = createInstalledExtension("eu.kanade.tachiyomi.extension.ar.teamx", listOf(externalTeamX))

        val sourceManager = createSourceManager()

        for (i in 1..5) {
            val isExternal = i % 2 == 1
            val expectedName = if (isExternal) "External Team X" else "Team X"
            installedExtensionsFlow.value = if (isExternal) listOf(extension) else emptyList()
            sourceManager.sources.first { list -> list.any { it.id == teamXId && it.name == expectedName } }

            val allSources = sourceManager.getAll()
            val ids = allSources.map { it.id }
            ids.distinct().size shouldBe ids.size
            ids.count { it == teamXId } shouldBe 1
        }
    }

    @Test
    fun `verify internal-only TeamX remains visible with Arabic enabled`() = runTest {
        installedExtensionsFlow.value = emptyList()
        val sourceManager = createSourceManager()
        val repository = SourceRepositoryImpl(sourceManager, mockk(relaxed = true))
        val getEnabledSources = GetEnabledSources(repository, sourcePreferences)

        sourcePreferences.enabledLanguages.set(setOf("ar"))

        val enabledList = getEnabledSources.subscribe().first { list -> list.any { it.id == teamXId } }
        enabledList.any { it.id == teamXId && it.name == "Team X" } shouldBe true
    }

    @Test
    fun `verify language filtering remains separate from provider fallback`() = runTest {
        val externalTeamX = TestCatalogueSource(id = teamXId, name = "External Team X", lang = "ar")
        val extension = createInstalledExtension("eu.kanade.tachiyomi.extension.ar.teamx", listOf(externalTeamX))

        installedExtensionsFlow.value = listOf(extension)
        val sourceManager = createSourceManager()
        val repository = SourceRepositoryImpl(sourceManager, mockk(relaxed = true))
        val getEnabledSources = GetEnabledSources(repository, sourcePreferences)

        // When Arabic is disabled, TeamX is filtered out
        sourcePreferences.enabledLanguages.set(setOf("en"))
        getEnabledSources.subscribe().first().any { it.id == teamXId } shouldBe false

        // When external is removed while Arabic is disabled, TeamX is still filtered out
        installedExtensionsFlow.value = emptyList()
        sourceManager.sources.first { list -> list.any { it.id == teamXId && it.name == "Team X" } }
        getEnabledSources.subscribe().first().any { it.id == teamXId } shouldBe false

        // When Arabic is enabled, internal TeamX becomes visible
        sourcePreferences.enabledLanguages.set(setOf("ar"))
        getEnabledSources.subscribe().first { list -> list.any { it.id == teamXId } }.any { it.id == teamXId } shouldBe true
    }

    private fun createInstalledExtension(pkgName: String, sources: List<Source>): Extension.Installed {
        return Extension.Installed(
            name = "Test Extension",
            pkgName = pkgName,
            versionName = "1.0.0",
            versionCode = 1,
            libVersion = 1.4,
            lang = "ar",
            isNsfw = false,
            sources = sources,
            pkgFactory = null,
            icon = null,
            isShared = true,
        )
    }

    private class FakeStubSourceRepository : StubSourceRepository {
        private val stubSources = MutableStateFlow<List<StubSource>>(emptyList())
        override fun subscribeAll(): Flow<List<StubSource>> = stubSources
        override suspend fun getStubSource(id: Long): StubSource? = stubSources.value.find { it.id == id }
        override suspend fun upsertStubSource(id: Long, lang: String, name: String) {
            val list = stubSources.value.filterNot { it.id == id } + StubSource(id, lang, name)
            stubSources.value = list
        }
    }

    private class TestCatalogueSource(
        override val id: Long,
        override val name: String,
        override val lang: String = "ar",
    ) : CatalogueSource {
        override val supportsLatest: Boolean = true
        override fun getFilterList(): FilterList = FilterList()
        override suspend fun getPopularManga(page: Int): MangasPage = MangasPage(emptyList(), false)
        override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(emptyList(), false)
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = MangasPage(emptyList(), false)
        override suspend fun getMangaUpdate(
            manga: SManga,
            chapters: List<SChapter>,
            fetchDetails: Boolean,
            fetchChapters: Boolean,
        ): SMangaUpdate = SMangaUpdate(manga, chapters)
        override suspend fun getPageList(chapter: SChapter): List<Page> = emptyList()
    }
}
