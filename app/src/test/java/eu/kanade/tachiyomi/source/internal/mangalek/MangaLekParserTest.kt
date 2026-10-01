package eu.kanade.tachiyomi.source.internal.mangalek

import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException

class MangaLekParserTest {

    private lateinit var mangaLek: MangaLek

    @BeforeEach
    fun setUp() {
        mangaLek = MangaLek()
    }

    @Test
    fun `verify MangaLek source ID matches pinned baseline exact value`() {
        mangaLek.id shouldBe 918460697583900080L
        mangaLek.name shouldBe "مانجا ليك"
        mangaLek.lang shouldBe "ar"
        mangaLek.versionId shouldBe 1
    }

    @Test
    fun `HTTP 200 homepage redirect cannot become a successful archive page`() {
        val response = okhttp3.Response.Builder()
            .request(okhttp3.Request.Builder().url("${mangaLek.baseUrl}/").build())
            .protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
            .body("<a href='/manga/sample'>Sample</a>".toResponseBody()).build()
        assertThrows<IOException> { mangaLek.parseArchiveResponse(response) }
    }

    @Test
    fun `verify parseMangaListFromDocument extracts Madara manga entries`() {
        val html = """
            <div class="page-item-detail">
              <div class="post-title">
                <h3><a href="https://mangalik.net/manga/otherworldly-evil-monarch/">Otherworldly Evil Monarch</a></h3>
              </div>
              <div class="item-thumb">
                <img src="https://mangalik.net/covers/monarch.jpg"/>
              </div>
            </div>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://mangalik.net")
        val mangasPage = mangaLek.parseMangaListFromDocument(doc)

        mangasPage.mangas.size shouldBe 1
        val manga = mangasPage.mangas.first()
        manga.title shouldBe "Otherworldly Evil Monarch"
        manga.url shouldBe "/manga/otherworldly-evil-monarch"
        manga.thumbnail_url shouldBe "https://mangalik.net/covers/monarch.jpg"
    }

    @Test
    fun `verify chained catalogue-to-details-to-chapters pipeline correctly isolates manga URL from chapter links`() {
        // Card HTML containing both manga link AND nested chapter links with title attribute (matching live Tales of Demons and Gods homepage card)
        val catalogueHtml = """
            <div class="col-6 col-md-2 badge-pos-1">
              <div class="page-item-detail manga">
                <div id="manga-item-80" class="item-thumb c-image-hover">
                  <a href="https://mangalik.net/manga/tales-of-demons-and-gods/" title="Tales of Demons and Gods">
                    <img src="https://io.mangalik.net/covers/tales.jpg" alt="Tales of Demons and Gods"/>
                  </a>
                </div>
                <div class="item-summary">
                  <div class="post-title font-title">
                    <h3 class="h5">
                      <a href="https://mangalik.net/manga/tales-of-demons-and-gods/">Tales of Demons and Gods</a>
                    </h3>
                  </div>
                  <div class="chapter-item">
                    <span class="chapter font-meta">
                      <a href="https://mangalik.net/manga/tales-of-demons-and-gods/532/" class="btn-link"> 532 </a>
                    </span>
                    <span class="c-new-tag">
                      <a href="https://mangalik.net/manga/tales-of-demons-and-gods/532/" title="27 دقيقة ago"><img src="new.gif"/></a>
                    </span>
                  </div>
                </div>
              </div>
            </div>
        """.trimIndent()

        val catalogueDoc = Jsoup.parse(catalogueHtml, "https://mangalik.net")
        val mangasPage = mangaLek.parseMangaListFromDocument(catalogueDoc)

        mangasPage.mangas.size shouldBe 1
        val catalogueManga = mangasPage.mangas.first()

        // CRITICAL CHECK: Catalogue MUST extract the manga URL, NOT the chapter URL /532/
        catalogueManga.url shouldBe "/manga/tales-of-demons-and-gods/#80"
        catalogueManga.title shouldBe "Tales of Demons and Gods"

        // Next step in pipeline: Details page
        val detailsHtml = """
            <div class="post-title"><h1>Tales of Demons and Gods</h1></div>
            <div class="summary_image"><img src="https://io.mangalik.net/covers/tales.jpg"/></div>
            <div class="author-content"><a href="/manga-author/mad-snail/">Mad Snail</a></div>
            <div class="description-summary">Nie Li reborn with demon spirit book</div>
            <ul class="main version-chap">
              <li class="wp-manga-chapter">
                <a href="https://mangalik.net/manga/tales-of-demons-and-gods/532/">الفصل 532</a>
              </li>
            </ul>
        """.trimIndent()

        val detailsDoc = Jsoup.parse(detailsHtml, "https://mangalik.net")
        val updatedManga = mangaLek.parseMangaDetails(detailsDoc, catalogueManga)

        updatedManga.title shouldBe "Tales of Demons and Gods"
        updatedManga.author shouldBe "Mad Snail"
        updatedManga.description shouldBe "Nie Li reborn with demon spirit book"

        val chapters = mangaLek.parseChapters(detailsDoc)
        chapters.size shouldBe 1
        chapters.first().name shouldBe "الفصل 532"
        chapters.first().url shouldBe "/manga/tales-of-demons-and-gods/532/"
    }

    @Test
    fun `verify parseMangaDetails correctly parses Madara details HTML`() {
        val initialManga = SManga.create().apply {
            url = "/manga/otherworldly-evil-monarch/"
            title = "Otherworldly Evil Monarch"
        }

        val html = """
            <div class="post-title"><h1>Otherworldly Evil Monarch</h1></div>
            <div class="summary_image"><img src="https://mangalik.net/covers/monarch.jpg"/></div>
            <div class="description-summary">Reincarnated assassin story</div>
            <span>مستمر</span>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://mangalik.net")
        val updated = mangaLek.parseMangaDetails(doc, initialManga)

        updated.title shouldBe "Otherworldly Evil Monarch"
        updated.description shouldBe "Reincarnated assassin story"
        updated.status shouldBe SManga.ONGOING
        updated.thumbnail_url shouldBe "https://mangalik.net/covers/monarch.jpg"
    }

    @Test
    fun `verify parseChapters extracts Madara chapter list`() {
        val html = """
            <ul class="main version-chap">
              <li class="wp-manga-chapter">
                <a href="https://mangalik.net/manga/otherworldly-evil-monarch/258/">الفصل 258</a>
              </li>
              <li class="wp-manga-chapter">
                <a href="https://mangalik.net/manga/otherworldly-evil-monarch/257/">الفصل 257</a>
              </li>
            </ul>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://mangalik.net")
        val chapters = mangaLek.parseChapters(doc)

        chapters.size shouldBe 2
        chapters[0].name shouldBe "الفصل 258"
        chapters[0].url shouldBe "/manga/otherworldly-evil-monarch/258/"
        chapters[0].chapter_number shouldBe 258.0f

        chapters[1].name shouldBe "الفصل 257"
        chapters[1].url shouldBe "/manga/otherworldly-evil-monarch/257/"
        chapters[1].chapter_number shouldBe 257.0f
    }

    @Test
    fun `verify parsePagesFromDocument extracts valid Madara reader images without double-reading response body`() {
        val html = """
            <div class="reading-content">
              <div class="page-break"><img src="https://mangalik.net/pages/01.jpg"/></div>
              <div class="page-break"><img src="https://mangalik.net/pages/02.jpg"/></div>
            </div>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://mangalik.net")
        val pages = mangaLek.parsePagesFromDocument(doc)

        pages.size shouldBe 2
        pages[0].imageUrl shouldBe "https://mangalik.net/pages/01.jpg"
        pages[1].imageUrl shouldBe "https://mangalik.net/pages/02.jpg"
    }
}
