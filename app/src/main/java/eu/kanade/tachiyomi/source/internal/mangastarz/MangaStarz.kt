package eu.kanade.tachiyomi.source.internal.mangastarz

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URI
import java.time.LocalDate
import java.time.ZoneOffset

class MangaStarz(
    private val customClient: OkHttpClient? = null,
) : HttpSource() {

    override val name: String = "Manga Starz"

    override val lang: String = "ar"

    override val versionId: Int = 1

    override val baseUrl: String = "https://starzmanga.com"

    override val supportsLatest: Boolean = true

    override val id: Long by lazy { generateId(name, lang, versionId) }

    override val client: OkHttpClient get() = customClient ?: network.client

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
        .add("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
        .add("Referer", "$baseUrl/")

    override fun popularMangaRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/manga/?order=popular" else "$baseUrl/manga/page/$page/?order=popular"
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        return parseMangaListFromDocument(document)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/manga/?order=update" else "$baseUrl/manga/page/$page/?order=update"
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        return parseMangaListFromDocument(document)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/".toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) {
                addQueryParameter("s", query)
            } else {
                addPathSegment("manga")
            }
            if (page > 1) {
                addQueryParameter("page", page.toString())
            }
        }.build().toString()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        return parseMangaListFromDocument(document)
    }

    private fun parseMangaListFromDocument(document: Document): MangasPage {
        val elements = document.select(".listupd .bs .bsx, .bs .bsx, .soralist .bd .utao .itm, .utao, div.bsx, .post-item")
            .ifEmpty { document.select("a[href*=/manga/]") }

        val mangas = mutableListOf<SManga>()
        val seenUrls = mutableSetOf<String>()

        for (element in elements) {
            val link = element.selectFirst("a") ?: (if (element.tagName() == "a") element else null) ?: continue
            val href = link.attr("abs:href").ifBlank { link.attr("href") }
            if (href.isBlank() || !href.contains("/manga/") || href == "$baseUrl/manga/") continue

            val relativeUrl = try {
                val uri = URI(href)
                uri.path.removeSuffix("/")
            } catch (_: Exception) {
                href.removePrefix(baseUrl).removeSuffix("/")
            }
            if (relativeUrl.isBlank() || relativeUrl in seenUrls) continue
            seenUrls.add(relativeUrl)

            val title = element.selectFirst(".tt, .title, .entry-title, a[title]")?.text()
                ?.ifBlank { link.attr("title") }
                ?: link.text()
            if (title.isBlank()) continue

            val img = element.selectFirst("img")
            val thumbnail = img?.attr("abs:data-src")
                ?.ifBlank { img.attr("abs:data-lazy-src") }
                ?.ifBlank { img.attr("abs:src") }
                ?.ifBlank { img.attr("data-src") }
                ?.ifBlank { img.attr("src") }

            val manga = SManga.create().apply {
                this.url = relativeUrl
                this.title = title.trim()
                this.thumbnail_url = thumbnail?.trim()
            }
            mangas.add(manga)
        }

        val hasNextPage = document.selectFirst(".h24, .pagination .next, a.next, .nav-previous, [rel=next]") != null
        return MangasPage(mangas, hasNextPage)
    }

    public override fun mangaDetailsRequest(manga: SManga): Request {
        val fullUrl = if (manga.url.startsWith("http")) manga.url else "$baseUrl${manga.url}"
        return GET(fullUrl, headers)
    }

    public override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val manga = SManga.create()

        val title = document.selectFirst("h1.entry-title, .post-title h1, .thumb img")?.text()
            ?: document.selectFirst("h1")?.text()
        manga.title = title?.trim() ?: ""

        val img = document.selectFirst(".thumb img, .summary_image img, .seriestucon .thumb img")
        manga.thumbnail_url = img?.attr("abs:data-src")
            ?.ifBlank { img.attr("abs:data-lazy-src") }
            ?.ifBlank { img.attr("abs:src") }
            ?.ifBlank { img.attr("src") }

        manga.description = document.select(".entry-content, .manga-excerpt, .summary__content, .seriestuhead p")
            .text().trim()

        val authorElement = document.selectFirst(".imethod:contains(المؤلف), .imethod:contains(Author), .author-content")
        manga.author = authorElement?.text()?.replace("المؤلف:", "")?.replace("Author:", "")?.trim()

        val statusElement = document.selectFirst(".imethod:contains(الحالة), .imethod:contains(Status), .post-content_item:contains(Status)")
        val statusText = statusElement?.text().orEmpty().lowercase()
        manga.status = when {
            statusText.contains("مكتمل") || statusText.contains("completed") -> SManga.COMPLETED
            statusText.contains("مستمر") || statusText.contains("ongoing") -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }

        val genres = document.select(".mgen a, .genres-content a, .seriestuhead .mgen a").map { it.text().trim() }
        manga.genre = genres.filter { it.isNotBlank() }.joinToString(", ")

        return manga
    }

    public override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    public override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val chapterElements = document.select("#chapterlist ul li, .eplister ul li, #chapterlist li, .bxcl ul li")

        val chapters = mutableListOf<SChapter>()
        for (element in chapterElements) {
            val link = element.selectFirst("a") ?: continue
            val href = link.attr("abs:href").ifBlank { link.attr("href") }
            if (href.isBlank()) continue

            val relativeUrl = try {
                URI(href).path
            } catch (_: Exception) {
                href.removePrefix(baseUrl)
            }

            val chapterName = element.selectFirst(".chapternum, .chapname, a")?.text()?.trim() ?: link.text().trim()
            val chapterNumber = parseChapterNumber(chapterName)

            val dateText = element.selectFirst(".chapterdate, .date")?.text()?.trim()
            val dateUpload = parseDate(dateText)

            val chapter = SChapter.create().apply {
                url = relativeUrl
                name = chapterName
                chapter_number = chapterNumber
                date_upload = dateUpload
            }
            chapters.add(chapter)
        }
        return chapters
    }

    private fun parseChapterNumber(name: String): Float {
        val match = Regex("""(?:فصل|chapter|ch\.?)\s*([\d.]+)""", RegexOption.IGNORE_CASE).find(name)
            ?: Regex("""([\d.]+)""").find(name)
        return match?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
    }

    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        return try {
            LocalDate.parse(dateStr).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }

    public override fun pageListRequest(chapter: SChapter): Request {
        val fullUrl = if (chapter.url.startsWith("http")) chapter.url else "$baseUrl${chapter.url}"
        return GET(fullUrl, headers)
    }

    public override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val imgElements = document.select("#readerarea img, div#readerarea p img, .reader-area img")

        val pages = mutableListOf<Page>()
        var index = 0
        for (img in imgElements) {
            val src = img.attr("abs:src")
                .ifBlank { img.attr("abs:data-src") }
                .ifBlank { img.attr("abs:data-lazy-src") }
                .ifBlank { img.attr("src") }
                .ifBlank { img.attr("data-src") }

            if (src.isNotBlank() && !src.contains("logo") && !src.contains("banner")) {
                pages.add(Page(index++, "", src.trim()))
            }
        }
        if (pages.isEmpty()) {
            throw IOException("Manga Starz page list is empty or protected")
        }
        return pages
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")
}
