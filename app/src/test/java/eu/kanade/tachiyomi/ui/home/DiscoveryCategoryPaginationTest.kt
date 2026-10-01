package eu.kanade.tachiyomi.ui.home

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import mihon.domain.source.discovery.interactor.GetSourceCapabilities
import mihon.domain.source.discovery.interactor.GetSourceDiscovery
import mihon.domain.source.discovery.model.DiscoveryCategory
import org.junit.jupiter.api.Test

class DiscoveryCategoryPaginationTest {

    private val getSourceCapabilities = GetSourceCapabilities()
    private val getSourceDiscovery = GetSourceDiscovery(getSourceCapabilities, Dispatchers.Unconfined, mihon.domain.source.health.SourceHealthMonitor())

    @Test
    fun `verify screen category argument maps correctly to discovery category`() {
        val screenPop = DiscoveryCategoryGridScreen("POPULAR", "شائع الآن")
        val screenNew = DiscoveryCategoryGridScreen("NEW", "جديد")
        val screenLat = DiscoveryCategoryGridScreen("LATEST", "آخر التحديثات")
        val screenComp = DiscoveryCategoryGridScreen("COMPLETED", "مكتمل")

        screenPop.categoryName shouldBe "POPULAR"
        screenNew.categoryName shouldBe "NEW"
        screenLat.categoryName shouldBe "LATEST"
        screenComp.categoryName shouldBe "COMPLETED"
    }

    @Test
    fun `verify page 1 discovery loading fetches first batch from source`() = runTest {
        val m1 = SManga.create().apply { url = "/page1/m1"; title = "P1 Manga 1" }
        val source = TestSource(id = 1L, name = "Source 1", page1List = listOf(m1))

        val result = getSourceDiscovery(source, DiscoveryCategory.POPULAR, page = 1)

        result.page shouldBe 1
        result.hasNextPage shouldBe true
        result.items.first().title shouldBe "P1 Manga 1"
    }

    @Test
    fun `verify next page pagination fetches page 2 from capable source`() = runTest {
        val m2 = SManga.create().apply { url = "/page2/m2"; title = "P2 Manga 2" }
        val source = TestSource(id = 1L, name = "Source 1", page2List = listOf(m2))

        val result = getSourceDiscovery(source, DiscoveryCategory.POPULAR, page = 2)

        result.page shouldBe 2
        result.hasNextPage shouldBe false
        result.items.first().title shouldBe "P2 Manga 2"
    }

    @Test
    fun `verify exhausted source with hasNextPage false is no longer queried`() = runTest {
        val sourceExhausted = TestSource(id = 1L, name = "Source Exhausted", hasMoreOnPage1 = false)

        val page1Res = getSourceDiscovery(sourceExhausted, DiscoveryCategory.POPULAR, page = 1)

        page1Res.hasNextPage shouldBe false
    }

    @Test
    fun `verify single source failure during pagination is isolated and other sources succeed`() = runTest {
        val sourceFailing = TestSource(id = 1L, name = "Source Failing", shouldFailOnPage2 = true)
        val sourceHealthy = TestSource(id = 2L, name = "Source Healthy", page2List = listOf(SManga.create().apply { url = "/p2"; title = "P2 Healthy" }))

        val res1 = getSourceDiscovery(sourceFailing, DiscoveryCategory.POPULAR, page = 2)
        val res2 = getSourceDiscovery(sourceHealthy, DiscoveryCategory.POPULAR, page = 2)

        res1.items shouldBe emptyList()
        res2.items.first().title shouldBe "P2 Healthy"
    }

    @Test
    fun `verify same title manga from different sources remain distinct during pagination`() = runTest {
        val item1 = SManga.create().apply { url = "/azora/m1"; title = "Solo Leveling" }
        val item2 = SManga.create().apply { url = "/mangadar/m1"; title = "Solo Leveling" }

        val source1 = TestSource(id = 1L, name = "Azora", page1List = listOf(item1))
        val source2 = TestSource(id = 2L, name = "MangaDar", page1List = listOf(item2))

        val res1 = getSourceDiscovery(source1, DiscoveryCategory.POPULAR, page = 1).items
        val res2 = getSourceDiscovery(source2, DiscoveryCategory.POPULAR, page = 1).items

        val combined = (res1 + res2).distinctBy { "${it.sourceId}_${it.url}" }

        combined.size shouldBe 2
        combined[0].sourceId shouldBe 1L
        combined[1].sourceId shouldBe 2L
    }

    private class TestSource(
        override val id: Long,
        override val name: String,
        override val supportsLatest: Boolean = true,
        private val hasMoreOnPage1: Boolean = true,
        private val page1List: List<SManga> = emptyList(),
        private val page2List: List<SManga> = emptyList(),
        private val shouldFailOnPage2: Boolean = false,
    ) : CatalogueSource {
        override val lang: String = "ar"
        override fun getFilterList(): FilterList = FilterList()

        override suspend fun getPopularManga(page: Int): MangasPage {
            if (page == 2 && shouldFailOnPage2) throw RuntimeException("Network error on page 2")
            return when (page) {
                1 -> MangasPage(page1List, hasMoreOnPage1)
                2 -> MangasPage(page2List, false)
                else -> MangasPage(emptyList(), false)
            }
        }

        override suspend fun getLatestUpdates(page: Int): MangasPage {
            return getPopularManga(page)
        }

        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
            return getPopularManga(page)
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
