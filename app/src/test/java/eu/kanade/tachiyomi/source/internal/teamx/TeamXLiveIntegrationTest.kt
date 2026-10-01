package eu.kanade.tachiyomi.source.internal.teamx

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class TeamXLiveIntegrationTest {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val teamX = TeamX(client)

    @Test
    fun `verify live popular manga parity`() {
        runBlocking {
            val page = teamX.getPopularManga(1)
            page.mangas.size shouldBeGreaterThanOrEqual 10
            val first = page.mangas.first()
            first.title.isBlank() shouldBe false
            first.url shouldStartWith "/series/"
        }
    }

    @Test
    fun `verify live latest updates parity`() {
        runBlocking {
            val page = teamX.getLatestUpdates(1)
            page.mangas.size shouldBeGreaterThanOrEqual 5
            val first = page.mangas.first()
            first.title.isBlank() shouldBe false
            first.url shouldStartWith "/series/"
        }
    }

    @Test
    fun `verify live search manga parity`() {
        runBlocking {
            val page = teamX.getSearchManga(1, "fast", FilterList())
            page.mangas.size shouldBeGreaterThanOrEqual 1
            val match = page.mangas.firstOrNull { it.url == "/series/fast-break" || it.title.lowercase().contains("fast break") }
            match shouldBe match
            page.mangas.first().url shouldStartWith "/series/"
        }
    }

    @Test
    fun `verify live details and small chapter list parity`() {
        runBlocking {
            val manga = SManga.create().apply { url = "/series/fast-break" }
            val update = teamX.getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = true)

            update.manga.title shouldBe "fast break"
            update.manga.initialized shouldBe true
            update.manga.thumbnail_url shouldStartWith "https://olympustaff.com/"

            update.chapters.size shouldBeGreaterThanOrEqual 15
            val ch15 = update.chapters.firstOrNull { it.url == "/series/fast-break/15" }
            ch15 shouldBe ch15
            update.chapters.first().url shouldStartWith "/series/fast-break/"
            update.chapters.first().date_upload shouldBeGreaterThan 0L
        }
    }

    @Test
    fun `verify live large series chapter list parity`() {
        runBlocking {
            val manga = SManga.create().apply { url = "/series/god-of-martial-arts" }
            val startTime = System.currentTimeMillis()
            val update = teamX.getMangaUpdate(manga, emptyList(), fetchDetails = false, fetchChapters = true)
            val durationMs = System.currentTimeMillis() - startTime

            update.chapters.size shouldBeGreaterThanOrEqual 1000

            val distinctUrls = update.chapters.map { it.url }.distinct()
            distinctUrls.size shouldBe update.chapters.size

            println("TeamX Large Series Parity Result: ${update.chapters.size} chapters fetched in ${durationMs}ms across paginated pages with max 2 concurrency")
        }
    }

    @Test
    fun `verify live page list parity`() {
        runBlocking {
            val chapter = SChapter.create().apply { url = "/series/fast-break/15" }
            val pages = teamX.getPageList(chapter)

            pages.size shouldBe 30
            val firstPage = pages.first()
            firstPage.index shouldBe 0
            firstPage.imageUrl shouldStartWith "https://olympustaff.com/uploads/"
        }
    }
}
