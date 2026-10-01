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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.test.runTest
import mihon.domain.source.discovery.interactor.GetSourceCapabilities
import mihon.domain.source.discovery.interactor.GetSourceDiscovery
import mihon.domain.source.discovery.model.CapabilitySupport
import mihon.domain.source.discovery.model.DiscoveryCategory
import org.junit.jupiter.api.Test

class HomeDiscoveryAggregationTest {

    private val getSourceCapabilities = GetSourceCapabilities()
    private val getSourceDiscovery = GetSourceDiscovery(getSourceCapabilities, Dispatchers.Unconfined, mihon.domain.source.health.SourceHealthMonitor())

    @Test
    fun `verify Popular aggregation preserves deterministic source interleaving`() = runTest {
        val mangaA1 = SManga.create().apply { url = "/manga/a1"; title = "Manga A1" }
        val mangaA2 = SManga.create().apply { url = "/manga/a2"; title = "Manga A2" }
        val mangaB1 = SManga.create().apply { url = "/manga/b1"; title = "Manga B1" }

        val sourceA = TestSource(id = 1L, name = "Source A", popularList = listOf(mangaA1, mangaA2))
        val sourceB = TestSource(id = 2L, name = "Source B", popularList = listOf(mangaB1))

        val resultA = getSourceDiscovery(sourceA, DiscoveryCategory.POPULAR)
        val resultB = getSourceDiscovery(sourceB, DiscoveryCategory.POPULAR)

        val interleaved = interleaveSources(listOf(resultA.items, resultB.items))

        interleaved.map { "${it.sourceName}:${it.title}" } shouldContainExactly listOf(
            "Source A:Manga A1",
            "Source B:Manga B1",
            "Source A:Manga A2",
        )
    }

    @Test
    fun `verify Latest aggregation includes only sources supporting latest updates`() = runTest {
        val mangaLatestA = SManga.create().apply { url = "/manga/latest-a"; title = "Latest A" }
        val sourceA = TestSource(id = 1L, name = "Source A", supportsLatest = true, latestList = listOf(mangaLatestA))
        val sourceB = TestSource(id = 2L, name = "Source B", supportsLatest = false)

        val sources = listOf(sourceA, sourceB)
        val latestResults = sources.map { source ->
            val caps = getSourceCapabilities(source)
            if (caps.supportsLatest == CapabilitySupport.SUPPORTED) {
                getSourceDiscovery(source, DiscoveryCategory.LATEST)
            } else null
        }.filterNotNull()

        latestResults.size shouldBe 1
        latestResults.first().sourceId shouldBe 1L
        latestResults.first().items.first().title shouldBe "Latest A"
    }

    @Test
    fun `verify same-title manga from DIFFERENT sources remain distinct and unmerged`() = runTest {
        val mangaA = SManga.create().apply { url = "/manga/solo-leveling-azora"; title = "Solo Leveling" }
        val mangaB = SManga.create().apply { url = "/manga/solo-leveling-mangadar"; title = "Solo Leveling" }

        val sourceA = TestSource(id = 1L, name = "Azora", popularList = listOf(mangaA))
        val sourceB = TestSource(id = 2L, name = "MangaDar", popularList = listOf(mangaB))

        val resA = getSourceDiscovery(sourceA, DiscoveryCategory.POPULAR).items
        val resB = getSourceDiscovery(sourceB, DiscoveryCategory.POPULAR).items

        val combined = resA + resB

        combined.size shouldBe 2
        combined[0].sourceId shouldBe 1L
        combined[1].sourceId shouldBe 2L
        combined[0].url shouldBe "/manga/solo-leveling-azora"
        combined[1].url shouldBe "/manga/solo-leveling-mangadar"
        (combined[0] == combined[1]) shouldBe false
    }

    @Test
    fun `verify single source failure is isolated and does not fail other sources`() = runTest {
        val sourceA = TestSource(id = 1L, name = "Source A", popularList = listOf(SManga.create().apply { url = "/a"; title = "A" }))
        val sourceB = TestSource(id = 2L, name = "Source B Failing", shouldFail = true)
        val sourceC = TestSource(id = 3L, name = "Source C", popularList = listOf(SManga.create().apply { url = "/c"; title = "C" }))

        val sources = listOf(sourceA, sourceB, sourceC)
        val semaphore = Semaphore(2)

        val results = sources.map { source ->
            async {
                semaphore.withPermit {
                    getSourceDiscovery(source, DiscoveryCategory.POPULAR)
                }
            }
        }.awaitAll()

        results.filter { it.items.isNotEmpty() }.map { it.sourceId } shouldContainExactly listOf(1L, 3L)
    }

    @Test
    fun `verify all sources failing produces empty result list without crashing`() = runTest {
        val sourceA = TestSource(id = 1L, name = "Source A", shouldFail = true)
        val sourceB = TestSource(id = 2L, name = "Source B", shouldFail = true)

        val sources = listOf(sourceA, sourceB)
        val results = sources.map { source ->
            getSourceDiscovery(source, DiscoveryCategory.POPULAR)
        }

        results.all { it.items.isEmpty() } shouldBe true
    }

    @Test
    fun `verify unsupported NEW category produces empty result from phase 05 6 1 foundation`() = runTest {
        val source = TestSource(id = 1L, name = "Source A")
        val result = getSourceDiscovery(source, DiscoveryCategory.NEW)
        result.items shouldBe emptyList()
        result.hasNextPage shouldBe false
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
            if (shouldFail) throw RuntimeException("Network error")
            return MangasPage(popularList, false)
        }

        override suspend fun getLatestUpdates(page: Int): MangasPage {
            if (shouldFail) throw RuntimeException("Network error")
            return MangasPage(latestList, false)
        }

        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
            if (shouldFail) throw RuntimeException("Network error")
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
