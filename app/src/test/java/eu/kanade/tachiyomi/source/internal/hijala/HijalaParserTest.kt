package eu.kanade.tachiyomi.source.internal.hijala

import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HijalaParserTest {

    private lateinit var hijala: Hijala

    @BeforeEach
    fun setUp() {
        hijala = Hijala()
    }

    @Test
    fun `verify Hijala source ID matches pinned baseline exact value`() {
        hijala.id shouldBe 917436262447415426L
        hijala.name shouldBe "Hijala"
        hijala.lang shouldBe "ar"
        hijala.versionId shouldBe 2
    }

    @Test
    fun `verify popularMangaRequest builds valid pagination URLs`() {
        val req1 = hijala.popularMangaRequest(1)
        req1.url.toString() shouldBe "https://hijala.com/manga/?order=popular"

        val req2 = hijala.popularMangaRequest(2)
        req2.url.toString() shouldBe "https://hijala.com/manga/?page=2&order=popular"
    }

    @Test
    fun `verify parseMangaListFromDocument extracts manga entries and ignores template links`() {
        val html = """
            <div class="listupd">
              <div class="bsx">
                <a href="https://hijala.com/solo-leveling/" title="Solo Leveling">
                  <div class="tt">Solo Leveling</div>
                  <img src="https://hijala.com/covers/solo.jpg"/>
                </a>
              </div>
            </div>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://hijala.com")
        val mangasPage = hijala.parseMangaListFromDocument(doc)

        mangasPage.mangas.size shouldBe 1
        val manga = mangasPage.mangas.first()
        manga.title shouldBe "Solo Leveling"
        manga.url shouldBe "/solo-leveling/"
        manga.thumbnail_url shouldBe "https://hijala.com/covers/solo.jpg"
    }

    @Test
    fun `verify parseMangaDetails correctly parses details HTML and extracts data-src cover when src is SVG placeholder`() {
        val initialManga = SManga.create().apply {
            url = "/the-bully-in-charge/"
            title = "The Bully In Charge"
            thumbnail_url = "https://hijala.com/wp-content/uploads/2024/05/0b6c08c2449bde04.webp"
        }

        // Live Hijala HTML with SVG placeholder in src and real image in data-src
        val html = """
            <h1 class="entry-title">The Bully In Charge</h1>
            <div class="thumb">
              <img src="data:image/svg+xml;base64,PHN2Zy..." data-src="https://hijala.com/wp-content/uploads/2024/05/0b6c08c2449bde04.webp" class="wp-post-image"/>
            </div>
            <div class="entry-content"><p>Action school life story</p></div>
            <span>Ongoing</span>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://hijala.com")
        val updated = hijala.parseMangaDetails(doc, initialManga)

        updated.title shouldBe "The Bully In Charge"
        updated.description shouldBe "Action school life story"
        updated.status shouldBe SManga.ONGOING
        updated.thumbnail_url shouldBe "https://hijala.com/wp-content/uploads/2024/05/0b6c08c2449bde04.webp"
    }

    @Test
    fun `verify parseChapters filters hidden template link and extracts real chapters`() {
        val html = """
            <div id="chapterlist">
              <ul>
                <li>
                  <a href="https://hijala.com/#/chapter-{{number}}"><span class="chapternum">Template</span></a>
                </li>
                <li data-num="180">
                  <a href="https://hijala.com/solo-leveling-180/"><span class="chapternum">الفصل 180</span></a>
                </li>
                <li data-num="179">
                  <a href="https://hijala.com/solo-leveling-179/"><span class="chapternum">الفصل 179</span></a>
                </li>
              </ul>
            </div>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://hijala.com")
        val chapters = hijala.parseChapters(doc)

        chapters.size shouldBe 2
        chapters[0].name shouldBe "الفصل 180"
        chapters[0].url shouldBe "/solo-leveling-180/"
        chapters[0].chapter_number shouldBe 180.0f

        chapters[1].name shouldBe "الفصل 179"
        chapters[1].url shouldBe "/solo-leveling-179/"
        chapters[1].chapter_number shouldBe 179.0f
    }

    @Test
    fun `verify parsePagesFromDocument extracts valid reader images`() {
        val html = """
            <div id="readerarea">
              <img src="https://hijala.com/pages/01.jpg"/>
              <img src="https://hijala.com/pages/02.jpg"/>
            </div>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://hijala.com")
        val pages = hijala.parsePagesFromDocument(doc)

        pages.size shouldBe 2
        pages[0].imageUrl shouldBe "https://hijala.com/pages/01.jpg"
        pages[1].imageUrl shouldBe "https://hijala.com/pages/02.jpg"
    }
    @Test
    fun `search retains WordPress path pagination independently of catalogue query pagination`() {
        hijala.searchMangaRequest(2, "ma", eu.kanade.tachiyomi.source.model.FilterList()).url.toString() shouldBe "https://hijala.com/page/2/?s=ma"
    }

}
