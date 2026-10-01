package eu.kanade.tachiyomi.ui.home

import eu.kanade.tachiyomi.ui.home.HOME_DISCOVERY_PREVIEW_LIMIT
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import mihon.domain.source.discovery.model.DiscoveryCategory
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.MangaCover

class HomePreviewLimitAndHandoffTest {

    @BeforeEach
    fun setUp() {
        DiscoverySnapshotStore.clear()
    }

    @Test
    fun `verify preview limit constant equals 8`() {
        HOME_DISCOVERY_PREVIEW_LIMIT shouldBe 8
    }

    @Test
    fun `verify DiscoverySnapshotStore stores and retrieves category snapshots correctly`() {
        val item1 = HomeDiscoveryItem(mangaId = 1L, title = "Manga 1", coverData = MangaCover(1L, 1L, false, "http://cover1.jpg", 0L), sourceId = 100L, sourceName = "Azora")
        val item2 = HomeDiscoveryItem(mangaId = 2L, title = "Manga 2", coverData = MangaCover(2L, 1L, false, "http://cover2.jpg", 0L), sourceId = 200L, sourceName = "MangaDar")

        DiscoverySnapshotStore.setSnapshot(DiscoveryCategory.POPULAR, listOf(item1, item2))

        val retrieved = DiscoverySnapshotStore.getSnapshot(DiscoveryCategory.POPULAR)

        retrieved.size shouldBe 2
        retrieved.map { it.title } shouldContainExactly listOf("Manga 1", "Manga 2")
    }

    @Test
    fun `verify DiscoverySnapshotStore returns empty list for uninitialized category`() {
        val retrieved = DiscoverySnapshotStore.getSnapshot(DiscoveryCategory.COMPLETED)

        retrieved shouldBe emptyList()
    }

    @Test
    fun `verify preview items list is truncated to HOME_DISCOVERY_PREVIEW_LIMIT`() {
        val items = (1..15).map { index ->
            HomeDiscoveryItem(
                mangaId = index.toLong(),
                title = "Manga $index",
                coverData = MangaCover(index.toLong(), 1L, false, "http://cover.jpg", 0L),
                sourceId = 100L,
                sourceName = "Source",
            )
        }

        val truncated = items.take(HOME_DISCOVERY_PREVIEW_LIMIT)

        truncated.size shouldBe 8
        truncated.last().title shouldBe "Manga 8"
    }

    @Test
    fun `verify seed items and network page 1 merge safely without duplicates`() {
        val seedItem = HomeDiscoveryItem(mangaId = 1L, title = "Manga 1", coverData = MangaCover(1L, 1L, false, "http://cover1.jpg", 0L), sourceId = 100L, sourceName = "Azora")
        val page1NewItem = HomeDiscoveryItem(mangaId = 2L, title = "Manga 2", coverData = MangaCover(2L, 1L, false, "http://cover2.jpg", 0L), sourceId = 100L, sourceName = "Azora")

        val currentItems = listOf(seedItem)
        val networkItems = listOf(seedItem, page1NewItem)

        val merged = (currentItems + networkItems).distinctBy { "${it.sourceId}_${it.mangaId}" }

        merged.size shouldBe 2
        merged.map { it.mangaId } shouldContainExactly listOf(1L, 2L)
    }

    @Test
    fun `verify same-title manga from different sources remain distinct during snapshot handoff`() {
        val itemA = HomeDiscoveryItem(mangaId = 10L, title = "Solo Leveling", coverData = MangaCover(10L, 1L, false, "http://coverA.jpg", 0L), sourceId = 100L, sourceName = "Azora")
        val itemB = HomeDiscoveryItem(mangaId = 20L, title = "Solo Leveling", coverData = MangaCover(20L, 1L, false, "http://coverB.jpg", 0L), sourceId = 200L, sourceName = "MangaDar")

        DiscoverySnapshotStore.setSnapshot(DiscoveryCategory.LATEST, listOf(itemA, itemB))

        val retrieved = DiscoverySnapshotStore.getSnapshot(DiscoveryCategory.LATEST)

        retrieved.size shouldBe 2
        retrieved[0].sourceId shouldBe 100L
        retrieved[1].sourceId shouldBe 200L
        (retrieved[0] == retrieved[1]) shouldBe false
    }
}
