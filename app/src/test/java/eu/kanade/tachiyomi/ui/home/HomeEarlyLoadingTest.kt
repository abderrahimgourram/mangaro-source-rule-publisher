package eu.kanade.tachiyomi.ui.home

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import mihon.domain.source.discovery.interactor.GetSourceCapabilities
import mihon.domain.source.discovery.interactor.GetSourceDiscovery
import mihon.domain.source.discovery.model.DiscoveryCategory
import org.junit.jupiter.api.Test

class HomeEarlyLoadingTest {

    private val getSourceCapabilities = GetSourceCapabilities()
    private val getSourceDiscovery = GetSourceDiscovery(getSourceCapabilities, Dispatchers.Unconfined, mihon.domain.source.health.SourceHealthMonitor())

    @Test
    fun `verify early batch fetches deterministic first wave sources`() = runTest {
        val s1Item = SManga.create().apply { url = "/manga/1"; title = "Manga 1" }
        val s2Item = SManga.create().apply { url = "/manga/2"; title = "Manga 2" }
        val s3Item = SManga.create().apply { url = "/manga/3"; title = "Manga 3" }

        val source1 = TestSource(id = 1L, name = "Source 1", popularList = listOf(s1Item))
        val source2 = TestSource(id = 2L, name = "Source 2", popularList = listOf(s2Item))
        val source3 = TestSource(id = 3L, name = "Source 3", popularList = listOf(s3Item))

        val allSources = listOf(source1, source2, source3)

        // Stage A: Early Wave (first 2 sources)
        val earlySources = allSources.take(2)
        val earlyPopularResults = earlySources.map { getSourceDiscovery(it, DiscoveryCategory.POPULAR) }

        earlyPopularResults.map { it.sourceId } shouldContainExactly listOf(1L, 2L)
        earlyPopularResults.flatMap { it.items }.map { it.title } shouldContainExactly listOf("Manga 1", "Manga 2")
    }

    @Test
    fun `verify stage B final batch extends early batch results deterministically`() = runTest {
        val s1Item = SManga.create().apply { url = "/manga/1"; title = "Manga 1" }
        val s2Item = SManga.create().apply { url = "/manga/2"; title = "Manga 2" }

        val source1 = TestSource(id = 1L, name = "Source 1", popularList = listOf(s1Item))
        val source2 = TestSource(id = 2L, name = "Source 2", popularList = listOf(s2Item))

        val earlyPayload = listOf(getSourceDiscovery(source1, DiscoveryCategory.POPULAR))
        val remainingPayload = listOf(getSourceDiscovery(source2, DiscoveryCategory.POPULAR))

        val combined = earlyPayload.flatMap { it.items } + remainingPayload.flatMap { it.items }

        combined.map { it.title } shouldContainExactly listOf("Manga 1", "Manga 2")
    }

    @Test
    fun `verify failed first-wave source does not block early batch progression`() = runTest {
        val source1 = TestSource(id = 1L, name = "Failing Source 1", shouldFail = true)
        val source2 = TestSource(id = 2L, name = "Source 2", popularList = listOf(SManga.create().apply { url = "/manga/2"; title = "Manga 2" }))

        val earlySources = listOf(source1, source2)
        val results = earlySources.map { getSourceDiscovery(it, DiscoveryCategory.POPULAR) }

        results.size shouldBe 2
        results.find { it.sourceId == 1L }?.items shouldBe emptyList()
        results.find { it.sourceId == 2L }?.items?.first()?.title shouldBe "Manga 2"
    }

    @Test
    fun `verify Popular and Latest both receive early wave content`() = runTest {
        val popularManga = SManga.create().apply { url = "/manga/pop"; title = "Popular Manga" }
        val latestManga = SManga.create().apply { url = "/manga/lat"; title = "Latest Manga" }

        val source = TestSource(
            id = 1L,
            name = "Source 1",
            supportsLatest = true,
            popularList = listOf(popularManga),
            latestList = listOf(latestManga),
        )

        val popRes = getSourceDiscovery(source, DiscoveryCategory.POPULAR)
        val latRes = getSourceDiscovery(source, DiscoveryCategory.LATEST)

        popRes.items.first().title shouldBe "Popular Manga"
        latRes.items.first().title shouldBe "Latest Manga"
    }

    @Test
    fun `verify same-title manga from different sources remain distinct during early and final stages`() = runTest {
        val itemA = SManga.create().apply { url = "/manga/solo-a"; title = "Solo Leveling" }
        val itemB = SManga.create().apply { url = "/manga/solo-b"; title = "Solo Leveling" }

        val sourceA = TestSource(id = 1L, name = "Azora", popularList = listOf(itemA))
        val sourceB = TestSource(id = 2L, name = "MangaDar", popularList = listOf(itemB))

        val resA = getSourceDiscovery(sourceA, DiscoveryCategory.POPULAR).items
        val resB = getSourceDiscovery(sourceB, DiscoveryCategory.POPULAR).items

        val combined = (resA + resB).distinctBy { "${it.sourceId}_${it.url}" }

        combined.size shouldBe 2
        combined[0].sourceId shouldBe 1L
        combined[1].sourceId shouldBe 2L
    }

    private class TestSource(
        override val id: Long,
        override val name: String,
        override val supportsLatest: Boolean = true,
        private val popularList: List<SManga> = emptyList(),
        private val latestList: List<SManga> = emptyList(),
        private val shouldFail: Boolean = false,
    ) : CatalogueSource {
        override val lang: String = "ar"
        override fun getFilterList(): FilterList = FilterList()

        override suspend fun getPopularManga(page: Int): MangasPage {
            if (shouldFail) throw RuntimeException("Network failure")
            return MangasPage(popularList, false)
        }

        override suspend fun getLatestUpdates(page: Int): MangasPage {
            if (shouldFail) throw RuntimeException("Network failure")
            return MangasPage(latestList, false)
        }

        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
            if (shouldFail) throw RuntimeException("Network failure")
            return MangasPage(popularList, false)
        }

        override suspend fun getMangaUpdate(
            manga: SManga,
            chapters: List<SChapter>,
            fetchDetails: Boolean,
            fetchChapters: Boolean,
        ): SMangaUpdate = SMangaUpdate(manga, chapters)

        override suspend fun getPageList(chapter: SChapter): List<Page> = emptyList()
    }
}
