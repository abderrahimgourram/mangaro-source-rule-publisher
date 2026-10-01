package eu.kanade.tachiyomi.source.internal.teamx

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class TeamXParserTest {

    private val teamX = TeamX()

    @Test
    fun `verify source identity matches pinned teamx specifications`() {
        teamX.name shouldBe "Team X"
        teamX.lang shouldBe "ar"
        teamX.versionId shouldBe 1
        teamX.id shouldBe 4110737012647435874L
        teamX.toString() shouldBe "Team X (AR)"
    }

    @Test
    fun `verify popular manga parsing from html fixture`() {
        val html = """
            <html>
            <body>
            <div class="listupd">
                <div class="bs">
                    <div class="bsx">
                        <a href="https://olympustaff.com/series/fast-break" title="fast break">
                            <img src="https://olympustaff.com/images/manga/thumb.png" alt="fast break"/>
                            <div class="tt">fast break</div>
                        </a>
                    </div>
                </div>
            </div>
            <ul class="pagination">
                <li class="page-item"><a class="page-link" href="https://olympustaff.com/series/?page=2" rel="next">&rsaquo;</a></li>
            </ul>
            </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://olympustaff.com")
        val result = teamX.parseMangaListFromDocument(doc)

        result.mangas shouldHaveSize 1
        result.mangas.first().title shouldBe "fast break"
        result.mangas.first().url shouldBe "/series/fast-break"
        result.mangas.first().thumbnail_url shouldBe "https://olympustaff.com/images/manga/thumb.png"
        result.hasNextPage shouldBe true
    }

    @Test
    fun `search follows native tx pager and does not mistake previous for next`() {
        val card = """<a class="tx-card" href="/series/sample"><h3>Sample</h3><img src="/sample.jpg"></a>"""
        val previous = """<a class="tx-pager-btn" href="/search?keyword=ma&amp;page=11">السابق</a>"""
        val next = """<a class="tx-pager-btn" href="/search?keyword=ma&amp;page=12">التالي <i></i></a>"""
        teamX.parseMangaListFromDocument(Jsoup.parse("$card<div class='tx-pager'>$previous$next</div>", teamX.baseUrl)).hasNextPage shouldBe true
        teamX.parseMangaListFromDocument(Jsoup.parse("$card<div class='tx-pager'>$previous</div>", teamX.baseUrl)).hasNextPage shouldBe false
    }

    @Test
    fun `verify latest manga parsing from html fixture`() {
        val html = """
            <html>
            <body>
            <div class="last-chapter">
                <div class="box">
                    <div class="imgu">
                        <a href="https://olympustaff.com/series/reincarnation-of-the-fist-king">
                            <img src="https://olympustaff.com/images/manga/thumb2.png" alt="Reincarnation of the Fist King"/>
                        </a>
                    </div>
                    <div class="info">
                        <h3><a href="https://olympustaff.com/series/reincarnation-of-the-fist-king">Reincarnation of the Fist King</a></h3>
                    </div>
                </div>
            </div>
            </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://olympustaff.com")
        val result = teamX.parseMangaListFromDocument(doc)

        result.mangas shouldHaveSize 1
        result.mangas.first().title shouldBe "Reincarnation of the Fist King"
        result.mangas.first().url shouldBe "/series/reincarnation-of-the-fist-king"
        result.mangas.first().thumbnail_url shouldBe "https://olympustaff.com/images/manga/thumb2.png"
        result.hasNextPage shouldBe false
    }

    @Test
    fun `verify search manga parsing with tx-card fixture`() {
        val html = """
            <html>
            <body>
            <a href="https://olympustaff.com/series/fast-break" class="tx-card">
                <div class="tx-card-poster">
                    <img src="https://olympustaff.com/images/manga/thumb.png" alt="fast break"/>
                </div>
                <div class="tx-card-body">
                    <h3>fast break</h3>
                </div>
            </a>
            </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://olympustaff.com")
        val result = teamX.parseMangaListFromDocument(doc)

        result.mangas shouldHaveSize 1
        result.mangas.first().title shouldBe "fast break"
        result.mangas.first().url shouldBe "/series/fast-break"
    }

    @Test
    fun `verify manga details parsing`() {
        val html = """
            <html>
            <body>
            <h1 class="entry-title">fast break - مانجا مترجمة</h1>
            <div class="thumb">
                <img src="https://olympustaff.com/images/manga/cover.png"/>
            </div>
            <div class="description">
                لقد سقط فريق كرة السلة...
            </div>
            <div class="status">مستمر</div>
            <div class="mgen">
                <a href="/genre/sports">رياضة</a>
                <a href="/genre/action">أكشن</a>
            </div>
            </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://olympustaff.com")
        val manga = SManga.create().apply { url = "/series/fast-break" }
        val updated = teamX.parseMangaDetails(doc, manga)

        updated.title shouldBe "fast break"
        updated.thumbnail_url shouldBe "https://olympustaff.com/images/manga/cover.png"
        updated.description shouldBe "لقد سقط فريق كرة السلة..."
        updated.status shouldBe SManga.ONGOING
        updated.genre shouldBe "رياضة, أكشن"
        updated.initialized shouldBe true
    }

    @Test
    fun `verify small chapter list parsing`() {
        val html = """
            <html>
            <body>
            <div class="chapter-card" data-date="1660680000" data-number="15">
                <a href="https://olympustaff.com/series/fast-break/15" class="chapter-link">
                    <div class="chapter-number">الفصل 15</div>
                </a>
            </div>
            <div class="chapter-card" data-date="1660670000" data-number="14">
                <a href="https://olympustaff.com/series/fast-break/14" class="chapter-link">
                    <div class="chapter-number">الفصل 14</div>
                </a>
            </div>
            </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://olympustaff.com")
        val chapters = teamX.parseChaptersFromDocument(doc)

        chapters shouldHaveSize 2
        chapters[0].url shouldBe "/series/fast-break/15"
        chapters[0].name shouldBe "الفصل 15"
        chapters[0].date_upload shouldBe 1660680000000L
        chapters[1].url shouldBe "/series/fast-break/14"
        chapters[1].name shouldBe "الفصل 14"
        chapters[1].date_upload shouldBe 1660670000000L
    }

    @Test
    fun `verify locked chapters are skipped`() {
        val html = """
            <html>
            <body>
            <div class="chapter-card" data-date="1660680000" data-number="16">
                <a href="#" class="chapter-link" data-bs-toggle="modal" data-bs-target="#buyModel">
                    <i class="fa fa-lock"></i>
                    <div class="chapter-number">الفصل 16</div>
                </a>
            </div>
            <div class="chapter-card" data-date="1660670000" data-number="15">
                <a href="https://olympustaff.com/series/fast-break/15" class="chapter-link">
                    <div class="chapter-number">الفصل 15</div>
                </a>
            </div>
            </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://olympustaff.com")
        val chapters = teamX.parseChaptersFromDocument(doc)

        chapters shouldHaveSize 1
        chapters.first().url shouldBe "/series/fast-break/15"
    }

    @Test
    fun `verify additional chapter page urls extraction`() {
        val html = """
            <html>
            <body>
            <ul class="pagination">
                <li class="page-item active"><span class="page-link">1</span></li>
                <li class="page-item"><a class="page-link" href="https://olympustaff.com/series/god-of-martial-arts?page=2">2</a></li>
                <li class="page-item"><a class="page-link" href="https://olympustaff.com/series/god-of-martial-arts?page=3">3</a></li>
                <li class="page-item"><a class="page-link" href="https://olympustaff.com/series/god-of-martial-arts?page=4">4</a></li>
            </ul>
            </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://olympustaff.com")
        val pageUrls = teamX.getAdditionalChapterPageUrls(doc, "/series/god-of-martial-arts")

        pageUrls shouldBe listOf(
            "/series/god-of-martial-arts?page=2",
            "/series/god-of-martial-arts?page=3",
            "/series/god-of-martial-arts?page=4",
        )
    }

    @Test
    fun `verify duplicate chapter avoidance and order preservation`() {
        val page1 = listOf(
            SChapter.create().apply { url = "/series/test/2"; name = "الفصل 2" },
            SChapter.create().apply { url = "/series/test/1"; name = "الفصل 1" },
        )
        val page2 = listOf(
            SChapter.create().apply { url = "/series/test/2"; name = "الفصل 2" },
            SChapter.create().apply { url = "/series/test/0"; name = "الفصل 0" },
        )

        val combined = (page1 + page2).distinctBy { it.url }

        combined shouldHaveSize 3
        combined.map { it.url } shouldBe listOf("/series/test/2", "/series/test/1", "/series/test/0")
    }

    @Test
    fun `verify page list parsing with img src and canvas data-src`() {
        val html = """
            <html>
            <body>
            <div class="image_list">
                <img src="https://olympustaff.com/uploads/1.png" class="manga-chapter-img" />
                <canvas data-src="https://olympustaff.com/uploads/2.png" />
                <img src="https://olympustaff.com/uploads/btn_close.gif" />
                <img src="data:image/png;base64,123" />
            </div>
            </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://olympustaff.com")
        val pages = teamX.parsePagesFromDocument(doc)

        pages shouldHaveSize 2
        pages[0].index shouldBe 0
        pages[0].imageUrl shouldBe "https://olympustaff.com/uploads/1.png"
        pages[1].index shouldBe 1
        pages[1].imageUrl shouldBe "https://olympustaff.com/uploads/2.png"
    }

    @Test
    fun `verify chapter pagination concurrency ceiling does not exceed 2`() {
        runBlocking {
            val activeRequests = AtomicInteger(0)
            val maxActiveRequests = AtomicInteger(0)

            val tasks = (1..10).map {
                async {
                    teamX.chapterPaginationSemaphore.withPermit {
                        val current = activeRequests.incrementAndGet()
                        var max = maxActiveRequests.get()
                        while (current > max) {
                            if (maxActiveRequests.compareAndSet(max, current)) break
                            max = maxActiveRequests.get()
                        }
                        delay(50)
                        activeRequests.decrementAndGet()
                    }
                }
            }

            tasks.awaitAll()
            maxActiveRequests.get() shouldBe 2
        }
    }

    @Test
    fun `verify chapter pagination fails cleanly when one page fails`() {
        val page1Html = """
            <html>
            <body>
            <div class="chapter-card" data-date="1660680000" data-number="3">
                <a href="https://olympustaff.com/series/test/3" class="chapter-link"><div class="chapter-number">الفصل 3</div></a>
            </div>
            <ul class="pagination">
                <li class="page-item"><a class="page-link" href="https://olympustaff.com/series/test?page=2">2</a></li>
                <li class="page-item"><a class="page-link" href="https://olympustaff.com/series/test?page=3">3</a></li>
            </ul>
            </body>
            </html>
        """.trimIndent()

        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                if (url.contains("page=2")) {
                    throw IOException("Simulated page 2 failure")
                }
                val responseHtml = if (url.contains("page=3")) {
                    """
                        <html><body>
                        <div class="chapter-card" data-date="1660660000" data-number="1">
                            <a href="https://olympustaff.com/series/test/1" class="chapter-link"><div class="chapter-number">الفصل 1</div></a>
                        </div>
                        </body></html>
                    """.trimIndent()
                } else {
                    page1Html
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseHtml.toResponseBody("text/html".toMediaType()))
                    .build()
            }
            .build()

        val failingTeamX = TeamX(mockClient)
        val initialDoc = Jsoup.parse(page1Html, "https://olympustaff.com")

        assertThrows<Exception> {
            runBlocking {
                failingTeamX.parseChapters(initialDoc, "/series/test")
            }
        }
    }
}
