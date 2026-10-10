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
import org.junit.jupiter.api.assertThrows
import java.io.IOException

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

        val inputManga = SManga.create().apply { url = "/manga/solo-leveling/" }

        // Simulating fetchMangaDetails logic to assign url since it's now internal to the Observable
        val manga = source.mangaDetailsParse(response)

        // Test identity validation from the internal method
        val validateMethod = source.javaClass.getDeclaredMethod("validateAndSetMangaUrl", SManga::class.java, String::class.java, String::class.java)
        validateMethod.isAccessible = true
        validateMethod.invoke(source, manga, inputManga.url, response.request.url.toString())

        manga.url shouldBe "/manga/solo-leveling/"
        manga.title shouldBe "Solo Leveling"
        manga.thumbnail_url shouldBe "https://tempmanhwa.starzmanga.com/covers/solo-leveling.jpg"
        manga.description shouldBe "In a world where hunters must battle deadly monsters, a weak hunter gets a second chance."
        manga.author shouldBe "Chugong"
        manga.status shouldBe SManga.COMPLETED
        manga.genre shouldBe "Action, Fantasy"
    }

    @Test
    fun testMangaIdentityMismatch() {
        val inputManga = SManga.create().apply { url = "/manga/my-fake-manga/" }
        val manga = SManga.create()

        val validateMethod = source.javaClass.getDeclaredMethod("validateAndSetMangaUrl", SManga::class.java, String::class.java, String::class.java)
        validateMethod.isAccessible = true

        val exception = assertThrows<java.lang.reflect.InvocationTargetException> {
            validateMethod.invoke(source, manga, inputManga.url, "https://starzmanga.com/manga/solo-leveling/")
        }

        val rootCause = exception.targetException
        rootCause.javaClass shouldBe IOException::class.java
        rootCause.message shouldBe "Manga identity mismatch: requested my-fake-manga but got solo-leveling"
    }

    @Test
    fun testMangaIdentitySubstringCollision() {
        val inputManga = SManga.create().apply { url = "/manga/leveling/" }
        val manga = SManga.create()

        val validateMethod = source.javaClass.getDeclaredMethod("validateAndSetMangaUrl", SManga::class.java, String::class.java, String::class.java)
        validateMethod.isAccessible = true

        val exception = assertThrows<java.lang.reflect.InvocationTargetException> {
            validateMethod.invoke(source, manga, inputManga.url, "https://starzmanga.com/manga/solo-leveling/")
        }

        val rootCause = exception.targetException
        rootCause.javaClass shouldBe IOException::class.java
        rootCause.message shouldBe "Manga identity mismatch: requested leveling but got solo-leveling"
    }

    @Test
    fun testMangaIdentitySameDomainRedirect() {
        val inputManga = SManga.create().apply { url = "/manga/solo-leveling/" }
        val manga = SManga.create()

        val validateMethod = source.javaClass.getDeclaredMethod("validateAndSetMangaUrl", SManga::class.java, String::class.java, String::class.java)
        validateMethod.isAccessible = true

        val exception = assertThrows<java.lang.reflect.InvocationTargetException> {
            validateMethod.invoke(source, manga, inputManga.url, "https://starzmanga.com/manga/solo-leveling-new/")
        }

        val rootCause = exception.targetException
        rootCause.javaClass shouldBe IOException::class.java
        rootCause.message shouldBe "Manga identity mismatch: requested solo-leveling but got solo-leveling-new"
    }

    @Test
    fun testMangaIdentityBlankUrl() {
        val inputManga = SManga.create().apply { url = "/manga/solo-leveling/" }
        val manga = SManga.create()

        val validateMethod = source.javaClass.getDeclaredMethod("validateAndSetMangaUrl", SManga::class.java, String::class.java, String::class.java)
        validateMethod.isAccessible = true

        val exception = assertThrows<java.lang.reflect.InvocationTargetException> {
            validateMethod.invoke(source, manga, inputManga.url, "")
        }

        val rootCause = exception.targetException
        rootCause.javaClass shouldBe IOException::class.java
        rootCause.message shouldBe "Manga identity mismatch: empty fetched URL"
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
    fun testParseChaptersExternalMangaLekLinksRejected() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head><title>Solo Leveling - Chapters</title></head>
            <body>
                <ul class="main version-chap">
                    <li class="wp-manga-chapter">
                        <a href="https://mangalik.net/manga/solo-leveling/chapter-179/">فصل 179</a>
                    </li>
                </ul>
            </body>
            </html>
        """.trimIndent()

        val response = Response.Builder()
            .request(Request.Builder().url("https://starzmanga.com/manga/solo-leveling/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(html.toResponseBody("text/html".toMediaTypeOrNull()))
            .build()

        assertThrows<IOException> {
            source.chapterListParse(response)
        }
    }

    @Test
    fun testParseChaptersIdentityMismatchLinksRejected() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head><title>Solo Leveling - Chapters</title></head>
            <body>
                <ul class="main version-chap">
                    <li class="wp-manga-chapter">
                        <a href="https://starzmanga.com/manga/wrong-manga/chapter-1/">فصل 1</a>
                    </li>
                </ul>
            </body>
            </html>
        """.trimIndent()

        val response = Response.Builder()
            .request(Request.Builder().url("https://starzmanga.com/manga/solo-leveling/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(html.toResponseBody("text/html".toMediaTypeOrNull()))
            .build()

        assertThrows<IOException> {
            source.chapterListParse(response)
        }
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

    @Test
    fun testWrongDomainDocumentRejected() {
        val doc = Jsoup.parse("<html><body><div class='page-item-detail manga'><a href='https://mangalik.net/manga/test'>Test</a></div></body></html>", "https://mangalik.net/manga/")
        assertThrows<IOException> {
            source.parseMangaListFromDocument(doc)
        }
    }

    @Test
    fun testMisleadingHostnameLinksRejected() {
        val html = """
            <div class="c-tabs-item__content">
                <div class="page-item-detail manga">
                    <div class="post-title">
                        <a href="https://starzmanga.com.fake.com/manga/fake-title/">Fake Manga</a>
                    </div>
                </div>
            </div>
        """.trimIndent()
        val doc = Jsoup.parse(html, "https://starzmanga.com")
        val page = source.parseMangaListFromDocument(doc)
        page.mangas shouldHaveSize 0
    }

    @Test
    fun testMangaLekHtmlWithStarzStringEmbeddedRejected() {
        val html = """
            <!-- Related site: starzmanga.com -->
            <div class="page-item-detail manga">
                <div class="post-title">
                    <a href="https://mangalik.net/manga/other-manga/">MangaLek Title</a>
                </div>
            </div>
        """.trimIndent()
        val doc = Jsoup.parse(html, "https://mangalik.net/manga/")
        assertThrows<IOException> {
            source.parseMangaListFromDocument(doc)
        }
    }

    @Test
    fun testCloudflareChallengeHtmlRejected() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head><title>Just a moment...</title></head>
            <body>
                <div id="challenge-error-title">Enable JavaScript to continue</div>
            </body>
            </html>
        """.trimIndent()
        val doc = Jsoup.parse(html, "https://starzmanga.com")
        assertThrows<IOException> {
            source.parseMangaListFromDocument(doc)
        }
    }

    @Test
    fun testOriginValidationHelper() {
        MangaStarzHtmlFetcher.isValidOrigin("https://starzmanga.com") shouldBe true
        MangaStarzHtmlFetcher.isValidOrigin("https://www.starzmanga.com/manga/solo-leveling/") shouldBe true
        MangaStarzHtmlFetcher.isValidOrigin("http://starzmanga.com") shouldBe false
        MangaStarzHtmlFetcher.isValidOrigin("https://starzmanga.com.fake.com") shouldBe false
        MangaStarzHtmlFetcher.isValidOrigin("https://mangalik.net") shouldBe false
    }
}
