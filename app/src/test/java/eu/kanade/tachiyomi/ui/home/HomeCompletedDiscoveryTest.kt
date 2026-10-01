package eu.kanade.tachiyomi.ui.home

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Filter
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
import mihon.domain.source.discovery.model.CapabilitySupport
import mihon.domain.source.discovery.model.DiscoveryCategory
import org.junit.jupiter.api.Test

class HomeCompletedDiscoveryTest {

    private val getSourceCapabilities = GetSourceCapabilities()
    private val getSourceDiscovery = GetSourceDiscovery(getSourceCapabilities, Dispatchers.Unconfined, mihon.domain.source.health.SourceHealthMonitor())

    @Test
    fun `verify completed capability detection identifies sources with completed status filter`() = runTest {
        val completedSource = TestSource(id = 1L, name = "Azora", hasStatusFilter = true)
        val plainSource = TestSource(id = 2L, name = "PlainSource", hasStatusFilter = false)

        val caps1 = getSourceCapabilities(completedSource)
        val caps2 = getSourceCapabilities(plainSource)

        caps1.supportsCompletedFilter shouldBe CapabilitySupport.SUPPORTED
        caps2.supportsCompletedFilter shouldBe CapabilitySupport.UNSUPPORTED
    }

    @Test
    fun `verify completed discovery executes filter and returns completed manga`() = runTest {
        val mangaComp = SManga.create().apply { url = "/manga/completed-1"; title = "Completed Manhwa 1"; status = SManga.COMPLETED }
        val source = TestSource(id = 1L, name = "MangaDar", hasStatusFilter = true, completedList = listOf(mangaComp))

        val result = getSourceDiscovery(source, DiscoveryCategory.COMPLETED, page = 1)

        result.items.size shouldBe 1
        result.items.first().title shouldBe "Completed Manhwa 1"
        result.items.first().url shouldBe "/manga/completed-1"
    }

    @Test
    fun `verify unsupported completed source is safely skipped`() = runTest {
        val source = TestSource(id = 1L, name = "UnsupportedSource", hasStatusFilter = false)

        val result = getSourceDiscovery(source, DiscoveryCategory.COMPLETED, page = 1)

        result.items shouldBe emptyList()
        result.hasNextPage shouldBe false
    }

    @Test
    fun `verify single source failure during completed discovery is isolated`() = runTest {
        val source1 = TestSource(id = 1L, name = "FailingSource", hasStatusFilter = true, shouldFail = true)
        val source2 = TestSource(id = 2L, name = "WorkingSource", hasStatusFilter = true, completedList = listOf(SManga.create().apply { url = "/comp"; title = "Completed 2" }))

        val res1 = getSourceDiscovery(source1, DiscoveryCategory.COMPLETED)
        val res2 = getSourceDiscovery(source2, DiscoveryCategory.COMPLETED)

        res1.items shouldBe emptyList()
        res2.items.first().title shouldBe "Completed 2"
    }

    @Test
    fun `verify same title completed manga from different sources remain distinct`() = runTest {
        val item1 = SManga.create().apply { url = "/azora/solo"; title = "Solo Leveling" }
        val item2 = SManga.create().apply { url = "/mangadar/solo"; title = "Solo Leveling" }

        val source1 = TestSource(id = 1L, name = "Azora", hasStatusFilter = true, completedList = listOf(item1))
        val source2 = TestSource(id = 2L, name = "MangaDar", hasStatusFilter = true, completedList = listOf(item2))

        val res1 = getSourceDiscovery(source1, DiscoveryCategory.COMPLETED).items
        val res2 = getSourceDiscovery(source2, DiscoveryCategory.COMPLETED).items

        val combined = (res1 + res2).distinctBy { "${it.sourceId}_${it.url}" }

        combined.size shouldBe 2
        combined[0].sourceId shouldBe 1L
        combined[1].sourceId shouldBe 2L
    }

    @Test
    fun `verify popular and latest discovery behavior remains unchanged when completed added`() = runTest {
        val popManga = SManga.create().apply { url = "/pop"; title = "Popular 1" }
        val latManga = SManga.create().apply { url = "/lat"; title = "Latest 1" }
        val compManga = SManga.create().apply { url = "/comp"; title = "Completed 1" }

        val source = TestSource(
            id = 1L,
            name = "FullSource",
            supportsLatest = true,
            hasStatusFilter = true,
            popularList = listOf(popManga),
            latestList = listOf(latManga),
            completedList = listOf(compManga),
        )

        val popRes = getSourceDiscovery(source, DiscoveryCategory.POPULAR)
        val latRes = getSourceDiscovery(source, DiscoveryCategory.LATEST)
        val compRes = getSourceDiscovery(source, DiscoveryCategory.COMPLETED)

        popRes.items.first().title shouldBe "Popular 1"
        latRes.items.first().title shouldBe "Latest 1"
        compRes.items.first().title shouldBe "Completed 1"
    }

    private class TestSource(
        override val id: Long,
        override val name: String,
        override val supportsLatest: Boolean = true,
        private val hasStatusFilter: Boolean = true,
        private val popularList: List<SManga> = emptyList(),
        private val latestList: List<SManga> = emptyList(),
        private val completedList: List<SManga> = emptyList(),
        private val shouldFail: Boolean = false,
    ) : CatalogueSource {
        override val lang: String = "ar"

        override fun getFilterList(): FilterList {
            return if (hasStatusFilter) {
                FilterList(
                    StatusFilter(arrayOf("All", "Ongoing", "Completed", "Canceled")),
                )
            } else {
                FilterList()
            }
        }

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
            val statusFilter = filters.filterIsInstance<StatusFilter>().firstOrNull()
            return if (statusFilter != null && statusFilter.state == 2) {
                MangasPage(completedList, false)
            } else {
                MangasPage(popularList, false)
            }
        }

        override suspend fun getMangaUpdate(
            manga: SManga,
            chapters: List<SChapter>,
            fetchDetails: Boolean,
            fetchChapters: Boolean,
        ): SMangaUpdate = SMangaUpdate(manga, chapters)

        override suspend fun getPageList(chapter: SChapter): List<Page> = emptyList()
    }

    private class StatusFilter(values: Array<String>) : Filter.Select<String>("Status", values)
}
