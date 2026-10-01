package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.internal.teamx.TeamX
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import io.kotest.matchers.shouldBe
import mihon.domain.source.registry.DefaultInternalSourceRegistry
import mihon.domain.source.registry.DefaultSourceCollisionPolicy
import mihon.domain.source.registry.SourceOrigin
import mihon.domain.source.registry.SourcePreferenceMode
import org.junit.jupiter.api.Test

class AndroidSourceManagerRegistryTest {

    private val collisionPolicy = DefaultSourceCollisionPolicy()

    @Test
    fun `verify default collision policy selects EXTENSION when both internal and extension sources exist for same ID`() {
        val internalSource: Source = TestCatalogueSource(id = 100L, name = "Internal Source A")
        val extensionSource: Source = TestCatalogueSource(id = 100L, name = "Extension Source A")

        val resolution = collisionPolicy.resolveCollision(
            sourceId = 100L,
            internalSource = internalSource,
            extensionSource = extensionSource,
            preferenceMode = SourcePreferenceMode.EXTERNAL_PREFERRED,
        )

        resolution.isCollision shouldBe true
        resolution.selectedOrigin shouldBe SourceOrigin.EXTENSION
        resolution.selectedSource.name shouldBe "Extension Source A"
        resolution.fallbackSource?.name shouldBe "Internal Source A"
        resolution.fallbackOrigin shouldBe SourceOrigin.INTERNAL
    }

    @Test
    fun `verify collision policy selects INTERNAL when INTERNAL_PREFERRED preference is set`() {
        val internalSource: Source = TestCatalogueSource(id = 100L, name = "Internal Source A")
        val extensionSource: Source = TestCatalogueSource(id = 100L, name = "Extension Source A")

        val resolution = collisionPolicy.resolveCollision(
            sourceId = 100L,
            internalSource = internalSource,
            extensionSource = extensionSource,
            preferenceMode = SourcePreferenceMode.INTERNAL_PREFERRED,
        )

        resolution.isCollision shouldBe true
        resolution.selectedOrigin shouldBe SourceOrigin.INTERNAL
        resolution.selectedSource.name shouldBe "Internal Source A"
        resolution.fallbackSource?.name shouldBe "Extension Source A"
        resolution.fallbackOrigin shouldBe SourceOrigin.EXTENSION
    }

    @Test
    fun `verify resolution selects INTERNAL when extension is absent`() {
        val internalSource: Source = TestCatalogueSource(id = 100L, name = "Internal Source A")

        val resolution = collisionPolicy.resolveCollision(
            sourceId = 100L,
            internalSource = internalSource,
            extensionSource = null,
            preferenceMode = SourcePreferenceMode.EXTERNAL_PREFERRED,
        )

        resolution.isCollision shouldBe false
        resolution.selectedOrigin shouldBe SourceOrigin.INTERNAL
        resolution.selectedSource.name shouldBe "Internal Source A"
        resolution.fallbackSource shouldBe null
    }

    @Test
    fun `verify resolution selects EXTENSION when internal is absent`() {
        val extensionSource: Source = TestCatalogueSource(id = 100L, name = "Extension Source A")

        val resolution = collisionPolicy.resolveCollision(
            sourceId = 100L,
            internalSource = null,
            extensionSource = extensionSource,
            preferenceMode = SourcePreferenceMode.EXTERNAL_PREFERRED,
        )

        resolution.isCollision shouldBe false
        resolution.selectedOrigin shouldBe SourceOrigin.EXTENSION
        resolution.selectedSource.name shouldBe "Extension Source A"
        resolution.fallbackSource shouldBe null
    }

    @Test
    fun `verify zero-extension architecture allows internal source to be canonical when no extensions exist`() {
        val fakeInternalSource: Source = TestCatalogueSource(id = 999L, name = "Fake Internal TeamX")
        val registry = DefaultInternalSourceRegistry(listOf(fakeInternalSource))

        val sources = registry.getSources()
        sources.size shouldBe 1
        sources.first().id shouldBe 999L
        sources.first().name shouldBe "Fake Internal TeamX"
    }

    @Test
    fun `verify same ID never appears twice in source list`() {
        val internalSource: Source = TestCatalogueSource(id = 100L, name = "Internal A")
        val extensionSource: Source = TestCatalogueSource(id = 100L, name = "Extension A")

        val res = collisionPolicy.resolveCollision(
            sourceId = 100L,
            internalSource = internalSource,
            extensionSource = extensionSource,
            preferenceMode = SourcePreferenceMode.EXTERNAL_PREFERRED,
        )

        val sourcesList = listOf(res.selectedSource)
        sourcesList.map { it.id }.distinct().size shouldBe sourcesList.size
    }

    @Test
    fun `verify internal teamx source id is exact`() {
        val internalTeamX = TeamX()
        internalTeamX.id shouldBe 4110737012647435874L
        internalTeamX.name shouldBe "Team X"
        internalTeamX.lang shouldBe "ar"
        internalTeamX.versionId shouldBe 1
    }

    @Test
    fun `verify teamx collision resolution lifecycle when extension appears and disappears`() {
        val teamXId = 4110737012647435874L
        val internalTeamX = TeamX()
        val extensionTeamX = TestCatalogueSource(id = teamXId, name = "Extension Team X")

        // 1. Both present -> EXTENSION selected
        val bothRes = collisionPolicy.resolveCollision(
            sourceId = teamXId,
            internalSource = internalTeamX,
            extensionSource = extensionTeamX,
            preferenceMode = SourcePreferenceMode.EXTERNAL_PREFERRED,
        )
        bothRes.selectedOrigin shouldBe SourceOrigin.EXTENSION
        bothRes.selectedSource.name shouldBe "Extension Team X"
        bothRes.fallbackSource?.name shouldBe "Team X"

        // 2. Extension disappears -> INTERNAL selected automatically
        val internalOnlyRes = collisionPolicy.resolveCollision(
            sourceId = teamXId,
            internalSource = internalTeamX,
            extensionSource = null,
            preferenceMode = SourcePreferenceMode.EXTERNAL_PREFERRED,
        )
        internalOnlyRes.selectedOrigin shouldBe SourceOrigin.INTERNAL
        internalOnlyRes.selectedSource.name shouldBe "Team X"
        internalOnlyRes.fallbackSource shouldBe null

        // 3. Extension returns -> EXTENSION selected again
        val returnRes = collisionPolicy.resolveCollision(
            sourceId = teamXId,
            internalSource = internalTeamX,
            extensionSource = extensionTeamX,
            preferenceMode = SourcePreferenceMode.EXTERNAL_PREFERRED,
        )
        returnRes.selectedOrigin shouldBe SourceOrigin.EXTENSION
        returnRes.selectedSource.name shouldBe "Extension Team X"
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
