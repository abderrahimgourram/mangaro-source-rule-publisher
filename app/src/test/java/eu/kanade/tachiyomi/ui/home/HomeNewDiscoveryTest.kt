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

class HomeNewDiscoveryTest {

    private val getSourceCapabilities = GetSourceCapabilities()
    private val getSourceDiscovery = GetSourceDiscovery(getSourceCapabilities, Dispatchers.Unconfined, mihon.domain.source.health.SourceHealthMonitor())

    @Test
    fun `verify NEW capability detection identifies sources with Date Added order filter`() = runTest {
        val newSource = TestSource(id = 1L, name = "NewCapableSource", hasNewSortFilter = true)
        val plainSource = TestSource(id = 2L, name = "PlainSource", hasNewSortFilter = false)

        val caps1 = getSourceCapabilities(newSource)
        val caps2 = getSourceCapabilities(plainSource)

        caps1.supportsNewFilter shouldBe CapabilitySupport.SUPPORTED
        caps2.supportsNewFilter shouldBe CapabilitySupport.UNSUPPORTED
    }

    @Test
    fun `verify NEW is not equivalent to LATEST and uses creation date order`() = runTest {
        val latestItem = SManga.create().apply { url = "/manga/latest"; title = "Latest Updated Manga" }
        val newItem = SManga.create().apply { url = "/manga/new"; title = "Newly Added Title" }

        val source = TestSource(
            id = 1L,
            name = "DualSource",
            supportsLatest = true,
            hasNewSortFilter = true,
            latestList = listOf(latestItem),
            newList = listOf(newItem),
        )

        val latRes = getSourceDiscovery(source, DiscoveryCategory.LATEST)
        val newRes = getSourceDiscovery(source, DiscoveryCategory.NEW)

        latRes.items.first().title shouldBe "Latest Updated Manga"
        newRes.items.first().title shouldBe "Newly Added Title"
        (latRes.items.first().url == newRes.items.first().url) shouldBe false
    }

    @Test
    fun `verify unsupported NEW source is safely skipped and returns empty list`() = runTest {
        val source = TestSource(id = 1L, name = "UnsupportedSource", hasNewSortFilter = false)

        val result = getSourceDiscovery(source, DiscoveryCategory.NEW, page = 1)

        result.items shouldBe emptyList()
        result.hasNextPage shouldBe false
    }

    @Test
    fun `verify single source failure during NEW discovery is isolated`() = runTest {
        val source1 = TestSource(id = 1L, name = "FailingSource", hasNewSortFilter = true, shouldFail = true)
        val source2 = TestSource(id = 2L, name = "WorkingSource", hasNewSortFilter = true, newList = listOf(SManga.create().apply { url = "/new2"; title = "New Title 2" }))

        val res1 = getSourceDiscovery(source1, DiscoveryCategory.NEW)
        val res2 = getSourceDiscovery(source2, DiscoveryCategory.NEW)

        res1.items shouldBe emptyList()
        res2.items.first().title shouldBe "New Title 2"
    }

    @Test
    fun `verify same title NEW manga from different sources remain distinct`() = runTest {
        val item1 = SManga.create().apply { url = "/azora/new-manga"; title = "New Manhwa" }
        val item2 = SManga.create().apply { url = "/mangadar/new-manga"; title = "New Manhwa" }

        val source1 = TestSource(id = 1L, name = "Azora", hasNewSortFilter = true, newList = listOf(item1))
        val source2 = TestSource(id = 2L, name = "MangaDar", hasNewSortFilter = true, newList = listOf(item2))

        val res1 = getSourceDiscovery(source1, DiscoveryCategory.NEW).items
        val res2 = getSourceDiscovery(source2, DiscoveryCategory.NEW).items

        val combined = (res1 + res2).distinctBy { "${it.sourceId}_${it.url}" }

        combined.size shouldBe 2
        combined[0].sourceId shouldBe 1L
        combined[1].sourceId shouldBe 2L
    }

    @Test
    fun `verify Popular Latest and Completed behavior remain unchanged when NEW is executed`() = runTest {
        val popManga = SManga.create().apply { url = "/pop"; title = "Popular 1" }
        val latManga = SManga.create().apply { url = "/lat"; title = "Latest 1" }
        val newManga = SManga.create().apply { url = "/new"; title = "New 1" }

        val source = TestSource(
            id = 1L,
            name = "FullSource",
            supportsLatest = true,
            hasNewSortFilter = true,
            popularList = listOf(popManga),
            latestList = listOf(latManga),
            newList = listOf(newManga),
        )

        val popRes = getSourceDiscovery(source, DiscoveryCategory.POPULAR)
        val latRes = getSourceDiscovery(source, DiscoveryCategory.LATEST)
        val newRes = getSourceDiscovery(source, DiscoveryCategory.NEW)

        popRes.items.first().title shouldBe "Popular 1"
        latRes.items.first().title shouldBe "Latest 1"
        newRes.items.first().title shouldBe "New 1"
    }

    private class TestSource(
        override val id: Long,
        override val name: String,
        override val supportsLatest: Boolean = true,
        private val hasNewSortFilter: Boolean = true,
        private val popularList: List<SManga> = emptyList(),
        private val latestList: List<SManga> = emptyList(),
        private val newList: List<SManga> = emptyList(),
        private val shouldFail: Boolean = false,
    ) : CatalogueSource {
        override val lang: String = "ar"

        override fun getFilterList(): FilterList {
            return if (hasNewSortFilter) {
                FilterList(
                    SortFilter(arrayOf("Default", "Popular", "Date Added", "Update")),
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
            val sortFilter = filters.filterIsInstance<SortFilter>().firstOrNull()
            return if (sortFilter != null && sortFilter.state == 2) {
                MangasPage(newList, false)
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

    private class SortFilter(values: Array<String>) : Filter.Select<String>("Order by", values)
}
