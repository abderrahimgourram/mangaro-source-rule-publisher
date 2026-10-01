package mihon.domain.source.discovery

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
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

class SourceDiscoveryFoundationTest {

    private val getSourceCapabilities = GetSourceCapabilities()
    private val getSourceDiscovery = GetSourceDiscovery(getSourceCapabilities, health = mihon.domain.source.health.SourceHealthMonitor())

    @Test
    fun `verify source capability detection for source with latest and completed status filter`() {
        val mockSource = TestCatalogueSource(
            id = 100L,
            name = "Azora Test Source",
            supportsLatest = true,
            filterList = FilterList(
                TestStatusFilter(arrayOf("Ongoing", "Completed", "Cancelled")),
            ),
        )

        val caps = getSourceCapabilities(mockSource)

        caps.sourceId shouldBe 100L
        caps.supportsPopular shouldBe CapabilitySupport.SUPPORTED
        caps.supportsLatest shouldBe CapabilitySupport.SUPPORTED
        caps.supportsSearch shouldBe CapabilitySupport.SUPPORTED
        caps.supportsStatusFilter shouldBe CapabilitySupport.SUPPORTED
        caps.supportsCompletedFilter shouldBe CapabilitySupport.SUPPORTED
    }

    @Test
    fun `verify source capability detection for source without latest and status filters`() {
        val mockSource = TestCatalogueSource(
            id = 200L,
            name = "Basic Source",
            supportsLatest = false,
            filterList = FilterList(),
        )

        val caps = getSourceCapabilities(mockSource)

        caps.sourceId shouldBe 200L
        caps.supportsPopular shouldBe CapabilitySupport.SUPPORTED
        caps.supportsLatest shouldBe CapabilitySupport.UNSUPPORTED
        caps.supportsSearch shouldBe CapabilitySupport.SUPPORTED
        caps.supportsStatusFilter shouldBe CapabilitySupport.UNSUPPORTED
        caps.supportsCompletedFilter shouldBe CapabilitySupport.UNSUPPORTED
    }

    @Test
    fun `verify popular discovery querying returns mapped items`() = runTest {
        val manga1 = SManga.create().apply {
            url = "/manga/solo-leveling"
            title = "Solo Leveling"
            thumbnail_url = "https://example.com/cover.jpg"
        }
        val mockSource = TestCatalogueSource(
            id = 101L,
            name = "MangaDar",
            supportsLatest = true,
            popularMangas = listOf(manga1),
        )

        val result = getSourceDiscovery(mockSource, DiscoveryCategory.POPULAR, page = 1)

        result.sourceId shouldBe 101L
        result.items.size shouldBe 1
        result.items.first().title shouldBe "Solo Leveling"
        result.items.first().url shouldBe "/manga/solo-leveling"
        result.items.first().sourceName shouldBe "MangaDar"
    }

    @Test
    fun `verify unsupported category returns empty result safely`() = runTest {
        val mockSource = TestCatalogueSource(
            id = 202L,
            name = "No Latest Source",
            supportsLatest = false,
        )

        val result = getSourceDiscovery(mockSource, DiscoveryCategory.LATEST, page = 1)

        result.sourceId shouldBe 202L
        result.items shouldBe emptyList()
        result.hasNextPage shouldBe false
    }

    @Test
    fun `verify duplicate manga titles from DIFFERENT sources remain distinct`() = runTest {
        val item1 = SManga.create().apply { url = "/manga/solo-leveling-azora"; title = "Solo Leveling" }
        val item2 = SManga.create().apply { url = "/manga/solo-leveling-mangadar"; title = "Solo Leveling" }

        val sourceA = TestCatalogueSource(id = 1L, name = "Azora", popularMangas = listOf(item1))
        val sourceB = TestCatalogueSource(id = 2L, name = "MangaDar", popularMangas = listOf(item2))

        val resultA = getSourceDiscovery(sourceA, DiscoveryCategory.POPULAR)
        val resultB = getSourceDiscovery(sourceB, DiscoveryCategory.POPULAR)

        resultA.items.first().sourceId shouldBe 1L
        resultB.items.first().sourceId shouldBe 2L
        resultA.items.first().url shouldBe "/manga/solo-leveling-azora"
        resultB.items.first().url shouldBe "/manga/solo-leveling-mangadar"

        (resultA.items.first() == resultB.items.first()) shouldBe false
    }

    @Test
    fun `verify failure on one source does not impact other sources during discovery aggregation`() = runTest {
        val sourceA = TestCatalogueSource(id = 1L, name = "Source A", popularMangas = listOf(SManga.create().apply { url = "/manga/a"; title = "Title A" }))
        val sourceB = TestCatalogueSource(id = 2L, name = "Failing Source B", shouldFail = true)
        val sourceC = TestCatalogueSource(id = 3L, name = "Source C", popularMangas = listOf(SManga.create().apply { url = "/manga/c"; title = "Title C" }))

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

    private class TestStatusFilter(options: Array<String>) : Filter.Select<String>("Status", options)

    private class TestCatalogueSource(
        override val id: Long,
        override val name: String,
        override val supportsLatest: Boolean = true,
        private val filterList: FilterList = FilterList(),
        private val popularMangas: List<SManga> = emptyList(),
        private val latestMangas: List<SManga> = emptyList(),
        private val shouldFail: Boolean = false,
    ) : CatalogueSource {
        override val lang: String = "ar"

        override fun getFilterList(): FilterList = filterList

        override suspend fun getPopularManga(page: Int): MangasPage {
            if (shouldFail) throw RuntimeException("Network error simulated")
            return MangasPage(popularMangas, false)
        }

        override suspend fun getLatestUpdates(page: Int): MangasPage {
            if (shouldFail) throw RuntimeException("Network error simulated")
            return MangasPage(latestMangas, false)
        }

        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
            if (shouldFail) throw RuntimeException("Network error simulated")
            return MangasPage(popularMangas, false)
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
