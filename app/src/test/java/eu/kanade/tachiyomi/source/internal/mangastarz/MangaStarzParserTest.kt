package eu.kanade.tachiyomi.source.internal.mangastarz

import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

class MangaStarzParserTest {

    private val source = MangaStarz()

    private val sampleCatalogueHtml = """
        <!DOCTYPE html>
        <html>
        <head><title>Manga Starz</title></head>
        <body>
            <div class="c-tabs-item__content">
                <div class="page-item-detail manga">
                    <div class="item-thumb">
                        <a href="https://starzmanga.com/manga/solo-leveling/">
                            <img src="https://tempmanhwa.starzmanga.com/covers/solo-leveling.jpg" alt="Solo Leveling" />
                        </a>
                    </div>
                    <div class="post-title">
                        <a href="https://starzmanga.com/manga/solo-leveling/">Solo Leveling</a>
                    </div>
                </div>
                <div class="page-item-detail manga">
                    <div class="item-thumb">
                        <a href="https://starzmanga.com/manga/omniscient-reader/">
                            <img data-src="https://tempmanhwa.starzmanga.com/covers/omniscient.jpg" alt="Omniscient Reader" />
                        </a>
                    </div>
                    <div class="post-title">
                        <a href="https://starzmanga.com/manga/omniscient-reader/">Omniscient Reader</a>
                    </div>
                </div>
            </div>
            <div class="nav-previous"><a href="https://starzmanga.com/manga/page/2/">Next</a></div>
        </body>
        </html>
    """.trimIndent()

    private val sampleDetailsHtml = """
        <!DOCTYPE html>
        <html>
        <head><title>Solo Leveling - Manga Starz</title></head>
        <body>
            <div class="post-title">
                <h1>Solo Leveling</h1>
            </div>
            <div class="summary_image">
                <img src="https://tempmanhwa.starzmanga.com/covers/solo-leveling.jpg" />
            </div>
            <div class="summary__content">
                In a world where hunters must battle deadly monsters, a weak hunter gets a second chance.
            </div>
            <div class="author-content">
                <a href="#">Chugong</a>
            </div>
            <div class="mg_status">
                <div class="summary-content">مكتمل</div>
            </div>
            <div class="genres-content">
                <a href="#">Action</a>
                <a href="#">Fantasy</a>
            </div>
        </body>
        </html>
    """.trimIndent()

    private val sampleChaptersHtml = """
        <!DOCTYPE html>
        <html>
        <head><title>Solo Leveling - Chapters</title></head>
        <body>
            <ul class="main version-chap">
                <li class="wp-manga-chapter">
                    <a href="https://starzmanga.com/manga/solo-leveling/chapter-179/">فصل 179</a>
                    <span class="chapter-release-date"><i>2024-01-15</i></span>
                </li>
                <li class="wp-manga-chapter">
                    <a href="https://starzmanga.com/manga/solo-leveling/chapter-178/">فصل 178</a>
                    <span class="chapter-release-date"><i>2024-01-10</i></span>
                </li>
            </ul>
        </body>
        </html>
    """.trimIndent()

    private val samplePagesHtml = """
        <!DOCTYPE html>
        <html>
        <head><title>Solo Leveling Chapter 179</title></head>
        <body>
            <div class="reading-content">
                <div class="page-break">
                    <img data-src="https://tempmanhwa.starzmanga.com/pages/c179/001.jpg" />
                </div>
                <div class="page-break">
                    <img data-src="https://tempmanhwa.starzmanga.com/pages/c179/002.jpg" />
                </div>
                <div class="page-break">
                    <img data-src="https://tempmanhwa.starzmanga.com/pages/c179/003.jpg" />
                </div>
            </div>
        </body>
        </html>
    """.trimIndent()

    @Test
    fun testParseCatalogueHtml() {
        val doc = Jsoup.parse(sampleCatalogueHtml, "https://starzmanga.com")
        val page = source.parseMangaListFromDocument(doc)

        page.mangas shouldHaveSize 2
        page.hasNextPage shouldBe true

        val first = page.mangas.first()
        first.title shouldBe "Solo Leveling"
        first.url shouldBe "/manga/solo-leveling"
        first.thumbnail_url shouldBe "https://tempmanhwa.starzmanga.com/covers/solo-leveling.jpg"

        val second = page.mangas[1]
        second.title shouldBe "Omniscient Reader"
        second.url shouldBe "/manga/omniscient-reader"
        second.thumbnail_url shouldBe "https://tempmanhwa.starzmanga.com/covers/omniscient.jpg"
    }

    @Test
    fun testParseDetailsHtml() {
        val response = Response.Builder()
            .request(Request.Builder().url("https://starzmanga.com/manga/solo-leveling/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(sampleDetailsHtml.toResponseBody("text/html".toMediaTypeOrNull()))
            .build()

        val manga = source.mangaDetailsParse(response)
        manga.title shouldBe "Solo Leveling"
        manga.thumbnail_url shouldBe "https://tempmanhwa.starzmanga.com/covers/solo-leveling.jpg"
        manga.description shouldBe "In a world where hunters must battle deadly monsters, a weak hunter gets a second chance."
        manga.author shouldBe "Chugong"
        manga.status shouldBe SManga.COMPLETED
        manga.genre shouldBe "Action, Fantasy"
    }

    @Test
    fun testParseChaptersHtml() {
        val response = Response.Builder()
            .request(Request.Builder().url("https://starzmanga.com/manga/solo-leveling/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(sampleChaptersHtml.toResponseBody("text/html".toMediaTypeOrNull()))
            .build()

        val chapters = source.chapterListParse(response)
        chapters shouldHaveSize 2

        val first = chapters.first()
        first.name shouldBe "فصل 179"
        first.url shouldBe "/manga/solo-leveling/chapter-179/"
        first.chapter_number shouldBe 179f

        val second = chapters[1]
        second.name shouldBe "فصل 178"
        second.url shouldBe "/manga/solo-leveling/chapter-178/"
        second.chapter_number shouldBe 178f
    }

    @Test
    fun testParsePagesHtml() {
        val response = Response.Builder()
            .request(Request.Builder().url("https://starzmanga.com/manga/solo-leveling/chapter-179/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(samplePagesHtml.toResponseBody("text/html".toMediaTypeOrNull()))
            .build()

        val pages = source.pageListParse(response)
        pages shouldHaveSize 3

        pages[0].imageUrl shouldBe "https://tempmanhwa.starzmanga.com/pages/c179/001.jpg"
        pages[1].imageUrl shouldBe "https://tempmanhwa.starzmanga.com/pages/c179/002.jpg"
        pages[2].imageUrl shouldBe "https://tempmanhwa.starzmanga.com/pages/c179/003.jpg"
    }
}
