package eu.kanade.tachiyomi.source.internal.mangastarz

import android.app.Application
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.internal.util.SourceValidationUtil
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import java.io.IOException
import java.net.URI
import java.time.LocalDate
import java.time.ZoneOffset
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class MangaStarz(
    private val customClient: OkHttpClient? = null,
) : HttpSource() {

    private val application: Application by injectLazy()

    override val name: String = "Manga Starz"

    override val lang: String = "ar"

    override val versionId: Int = 1

    override val baseUrl: String = "https://starzmanga.com"

    override val supportsLatest: Boolean = true

    override val id: Long by lazy { generateId(name, lang, versionId) }

    override val client: OkHttpClient get() = customClient ?: network.client

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", runCatching { Injekt.get<NetworkHelper>().defaultUserAgentProvider() }.getOrDefault("Mozilla/5.0 (Linux; Android 14; Mobile)"))
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
        .add("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
        .add("Referer", "$baseUrl/")

    private fun checkMangaStarzOrigin(document: Document) {
        SourceValidationUtil.checkCloudflareOrError(document)
        val location = document.location().orEmpty().lowercase()
        if (location.isNotBlank()) {
            val uri = try { URI(location) } catch (_: Exception) { null }
            if (uri != null && uri.host != null && uri.host != "starzmanga.com" && uri.host != "www.starzmanga.com") {
                throw IOException("Cross-source domain contamination detected: $location is not starzmanga.com")
            }
        }
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val request = popularMangaRequest(page)
        return try {
            val response = client.newCall(request).awaitSuccess()
            popularMangaParse(response)
        } catch (e: Exception) {
            val context = runCatching { application.applicationContext }.getOrNull()
            if (context != null) {
                val headersMap = headers.toMultimap().mapValues { it.value.firstOrNull().orEmpty() }
                val doc = MangaStarzHtmlFetcher.fetchDocument(context, request.url.toString(), headersMap)
                parseMangaListFromDocument(doc)
            } else {
                throw e
            }
        }
    }

    override fun popularMangaRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/manga/?m_orderby=views" else "$baseUrl/manga/page/$page/?m_orderby=views"
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        return SourceValidationUtil.parseCatalogueResponse(response, ::parseMangaListFromDocument)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val request = latestUpdatesRequest(page)
        return try {
            val response = client.newCall(request).awaitSuccess()
            latestUpdatesParse(response)
        } catch (e: Exception) {
            val context = runCatching { application.applicationContext }.getOrNull()
            if (context != null) {
                val headersMap = headers.toMultimap().mapValues { it.value.firstOrNull().orEmpty() }
                val doc = MangaStarzHtmlFetcher.fetchDocument(context, request.url.toString(), headersMap)
                parseMangaListFromDocument(doc)
            } else {
                throw e
            }
        }
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/manga/?m_orderby=latest" else "$baseUrl/manga/page/$page/?m_orderby=latest"
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        return SourceValidationUtil.parseCatalogueResponse(response, ::parseMangaListFromDocument)
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val request = searchMangaRequest(page, query, filters)
        return try {
            val response = client.newCall(request).awaitSuccess()
            searchMangaParse(response)
        } catch (e: Exception) {
            val context = runCatching { application.applicationContext }.getOrNull()
            if (context != null) {
                val headersMap = headers.toMultimap().mapValues { it.value.firstOrNull().orEmpty() }
                val doc = MangaStarzHtmlFetcher.fetchDocument(context, request.url.toString(), headersMap)
                parseMangaListFromDocument(doc)
            } else {
                throw e
            }
        }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = if (query.isNotBlank()) {
            if (page == 1) {
                "$baseUrl/".toHttpUrl().newBuilder()
                    .addQueryParameter("s", query.trim())
                    .addQueryParameter("post_type", "wp-manga")
                    .build().toString()
            } else {
                "$baseUrl/page/$page/".toHttpUrl().newBuilder()
                    .addQueryParameter("s", query.trim())
                    .addQueryParameter("post_type", "wp-manga")
                    .build().toString()
            }
        } else {
            if (page == 1) "$baseUrl/manga/" else "$baseUrl/manga/page/$page/"
        }
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        return SourceValidationUtil.parseCatalogueResponse(response, ::parseMangaListFromDocument)
    }

    fun parseMangaListFromDocument(document: Document): MangasPage {
        checkMangaStarzOrigin(document)

        val elements = document.select(".page-item-detail.manga, div.page-item-detail, .c-tabs-item__content, .manga-item, .listupd .bs .bsx, .bs .bsx")
            .ifEmpty { document.select("div.post-title") }
            .ifEmpty { document.select("a[href*=/manga/]") }

        val mangas = mutableListOf<SManga>()
        val seenUrls = mutableSetOf<String>()

        for (element in elements) {
            val titleElement = element.selectFirst(".post-title a, h3.h4 a, h3 a, .tt, .title, .entry-title a")
                ?: element.selectFirst("a[href*=/manga/]")
                ?: (if (element.tagName() == "a") element else null)
                ?: continue

            val href = titleElement.attr("abs:href").ifBlank { titleElement.attr("href") }
            if (href.isBlank() || (!href.contains("starzmanga.com/manga/") && !href.startsWith("/manga/"))) continue

            val relativeUrl = try {
                val uri = URI(href)
                if (uri.host != null && uri.host != "starzmanga.com" && uri.host != "www.starzmanga.com") continue
                uri.path.removeSuffix("/")
            } catch (_: Exception) {
                href.removePrefix(baseUrl).removeSuffix("/")
            }
            if (relativeUrl.isBlank() || relativeUrl in seenUrls) continue
            seenUrls.add(relativeUrl)

            val title = titleElement.text().ifBlank { titleElement.attr("title") }
            if (title.isBlank()) continue

            val img = element.selectFirst(".item-thumb img, .thumb img, img")
            val thumbnail = extractImageUrl(img)

            val manga = SManga.create().apply {
                this.url = relativeUrl
                this.title = title.trim()
                this.thumbnail_url = thumbnail
            }
            mangas.add(manga)
        }

        val hasNextPage = document.selectFirst(".nav-previous a, .pagination .next, a.next, [rel=next], .h24") != null
        return MangasPage(mangas, hasNextPage)
    }

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return Observable.fromCallable {
            val request = mangaDetailsRequest(manga)
            try {
                val response = client.newCall(request).execute()
                val parsed = mangaDetailsParse(response)
                validateAndSetMangaUrl(parsed, manga.url, response.request.url.toString())
                parsed.apply { initialized = true }
            } catch (e: Exception) {
                val context = runCatching { application.applicationContext }.getOrNull()
                if (context != null) {
                    val fullUrl = if (manga.url.startsWith("http")) manga.url else "$baseUrl${manga.url}"
                    val headersMap = headers.toMultimap().mapValues { it.value.firstOrNull().orEmpty() }
                    val doc = runBlocking { MangaStarzHtmlFetcher.fetchDocument(context, fullUrl, headersMap) }
                    val parsed = parseMangaDetailsFromDocument(doc)
                    validateAndSetMangaUrl(parsed, manga.url, doc.location())
                    parsed.apply { initialized = true }
                } else {
                    throw e
                }
            }
        }
    }

    private fun validateAndSetMangaUrl(parsed: SManga, requestedUrl: String, fetchedUrl: String) {
        if (fetchedUrl.isBlank()) throw IOException("Manga identity mismatch: empty fetched URL")
        val uri = try { URI(fetchedUrl) } catch (_: Exception) { throw IOException("Manga identity mismatch: malformed fetched URL $fetchedUrl") }
        if (uri.scheme?.lowercase() != "https") throw IOException("Manga identity mismatch: non-HTTPS fetched URL $fetchedUrl")
        if (uri.host != "starzmanga.com" && uri.host != "www.starzmanga.com") {
            throw IOException("Cross-source domain contamination detected: $fetchedUrl is not starzmanga.com")
        }

        val reqSlug = try { java.net.URLDecoder.decode(requestedUrl.removeSuffix("/").substringAfterLast("/"), "UTF-8") } catch (_: Exception) { requestedUrl.removeSuffix("/").substringAfterLast("/") }
        val fetchSlug = try { java.net.URLDecoder.decode(fetchedUrl.removeSuffix("/").substringAfterLast("/"), "UTF-8") } catch (_: Exception) { fetchedUrl.removeSuffix("/").substringAfterLast("/") }

        if (reqSlug.isBlank() || fetchSlug.isBlank() || reqSlug != fetchSlug) {
            throw IOException("Manga identity mismatch: requested $reqSlug but got $fetchSlug")
        }

        parsed.url = requestedUrl
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        val fullUrl = if (manga.url.startsWith("http")) manga.url else "$baseUrl${manga.url}"
        return GET(fullUrl, headers)
    }

    public override fun mangaDetailsParse(response: Response): SManga {
        return response.use {
            val document = it.asJsoup()
            parseMangaDetailsFromDocument(document)
        }
    }

    fun parseMangaDetailsFromDocument(document: Document): SManga {
        checkMangaStarzOrigin(document)

        val manga = SManga.create()

        val title = document.selectFirst("h1.entry-title, .post-title h1, h1")?.text()
        manga.title = title?.trim() ?: ""

        val img = document.selectFirst(".summary_image img, .thumb img, .post-title img")
        manga.thumbnail_url = extractImageUrl(img)

        manga.description = document.select(".summary__content, .entry-content, .manga-excerpt, .description-summary")
            .text().trim()

        val authorElement = document.selectFirst(".author-content a, .author-content, .imethod:contains(المؤلف), .imethod:contains(Author)")
        manga.author = authorElement?.text()?.replace("المؤلف:", "")?.replace("Author:", "")?.trim()

        val statusElement = document.selectFirst(".mg_status .summary-content, .post-status .summary-content, .imethod:contains(الحالة)")
        val statusText = statusElement?.text().orEmpty().lowercase()
        manga.status = when {
            statusText.contains("مكتمل") || statusText.contains("completed") -> SManga.COMPLETED
            statusText.contains("مستمر") || statusText.contains("ongoing") -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }

        val genres = document.select(".genres-content a, .mgen a").map { el -> el.text().trim() }
        manga.genre = genres.filter { g -> g.isNotBlank() }.joinToString(", ")

        return manga
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        return Observable.fromCallable {
            val request = chapterListRequest(manga)
            try {
                val response = client.newCall(request).execute()
                chapterListParse(response)
            } catch (e: Exception) {
                val context = runCatching { application.applicationContext }.getOrNull()
                if (context != null) {
                    val fullUrl = if (manga.url.startsWith("http")) manga.url else "$baseUrl${manga.url}"
                    val headersMap = headers.toMultimap().mapValues { it.value.firstOrNull().orEmpty() }
                    val doc = runBlocking { MangaStarzHtmlFetcher.fetchDocument(context, fullUrl, headersMap) }
                    parseChapterListFromDocument(doc)
                } else {
                    throw e
                }
            }
        }
    }

    public override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    public override fun chapterListParse(response: Response): List<SChapter> {
        return response.use {
            val document = it.asJsoup()
            parseChapterListFromDocument(document, it.request.url.toString())
        }
    }

    fun parseChapterListFromDocument(document: Document, documentUrl: String = document.location()): List<SChapter> {
        checkMangaStarzOrigin(document)
        val mangaSlug = try { java.net.URLDecoder.decode(documentUrl.removeSuffix("/").substringAfterLast("/"), "UTF-8") } catch (_: Exception) { documentUrl.removeSuffix("/").substringAfterLast("/") }

        val chapterElements = document.select("li.wp-manga-chapter, .wp-manga-chapter, #chapterlist ul li, .eplister ul li")

        val chapters = mutableListOf<SChapter>()
        for (element in chapterElements) {
            val link = element.selectFirst("a") ?: continue
            val href = link.attr("abs:href").ifBlank { link.attr("href") }
            if (href.isBlank()) continue

            val relativeUrl = try {
                val uri = URI(href)
                if (uri.host != null && uri.host != "starzmanga.com" && uri.host != "www.starzmanga.com") continue
                if (uri.scheme?.lowercase() != "https") continue
                if (mangaSlug.isNotBlank() && !java.net.URLDecoder.decode(uri.path, "UTF-8").contains(mangaSlug)) continue
                uri.path
            } catch (_: Exception) {
                if (mangaSlug.isNotBlank() && !java.net.URLDecoder.decode(href, "UTF-8").contains(mangaSlug)) continue
                href.removePrefix(baseUrl)
            }

            val chapterName = link.text().trim()
            val chapterNumber = parseChapterNumber(chapterName)

            val dateText = element.selectFirst("span.chapter-release-date i, .chapterdate, .date")?.text()?.trim()
            val dateUpload = parseDate(dateText)

            val chapter = SChapter.create().apply {
                url = relativeUrl
                name = chapterName
                chapter_number = chapterNumber
                date_upload = dateUpload
            }
            chapters.add(chapter)
        }

        // If empty, try AJAX chapter fetch if Madara ajax endpoint is present
        if (chapters.isEmpty()) {
            val mangaId = document.selectFirst("div#manga-chapters-holder")?.attr("data-id")
            if (!mangaId.isNullOrBlank()) {
                val ajaxChapters = fetchChaptersViaAjax(mangaId, mangaSlug)
                if (ajaxChapters.isEmpty()) throw IOException("Manga Starz page list is empty or protected")
                return ajaxChapters
            }
            throw IOException("Manga Starz page list is empty or protected")
        }

        return chapters
    }

    private fun fetchChaptersViaAjax(mangaId: String, mangaSlug: String): List<SChapter> {
        val formBody = FormBody.Builder()
            .add("action", "manga_get_chapters")
            .add("manga", mangaId)
            .build()
        val request = POST("$baseUrl/wp-admin/admin-ajax.php", headers, formBody)
        return client.newCall(request).execute().use { response ->
            val document = response.asJsoup()
            val chapterElements = document.select("li.wp-manga-chapter, .wp-manga-chapter")
            chapterElements.mapNotNull { element ->
                val link = element.selectFirst("a") ?: return@mapNotNull null
                val href = link.attr("abs:href").ifBlank { link.attr("href") }
                if (href.isBlank()) return@mapNotNull null

                val relativeUrl = try {
                    val uri = URI(href)
                    if (uri.host != null && uri.host != "starzmanga.com" && uri.host != "www.starzmanga.com") {
                        return@mapNotNull null
                    }
                    if (uri.scheme?.lowercase() != "https") return@mapNotNull null
                    if (mangaSlug.isNotBlank() && !java.net.URLDecoder.decode(uri.path, "UTF-8").contains(mangaSlug)) return@mapNotNull null
                    uri.path
                } catch (_: Exception) {
                    if (mangaSlug.isNotBlank() && !java.net.URLDecoder.decode(href, "UTF-8").contains(mangaSlug)) return@mapNotNull null
                    href.removePrefix(baseUrl)
                }
                val chapterName = link.text().trim()
                val dateText = element.selectFirst("span.chapter-release-date i, .chapterdate")?.text()?.trim()

                SChapter.create().apply {
                    url = relativeUrl
                    name = chapterName
                    chapter_number = parseChapterNumber(chapterName)
                    date_upload = parseDate(dateText)
                }
            }
        }
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

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        return Observable.fromCallable {
            val request = pageListRequest(chapter)
            try {
                val response = client.newCall(request).execute()
                pageListParse(response)
            } catch (e: Exception) {
                val context = runCatching { application.applicationContext }.getOrNull()
                if (context != null) {
                    val fullUrl = if (chapter.url.startsWith("http")) chapter.url else "$baseUrl${chapter.url}"
                    val headersMap = headers.toMultimap().mapValues { it.value.firstOrNull().orEmpty() }
                    val doc = runBlocking { MangaStarzHtmlFetcher.fetchDocument(context, fullUrl, headersMap) }
                    parsePageListFromDocument(doc)
                } else {
                    throw e
                }
            }
        }
    }

    public override fun pageListRequest(chapter: SChapter): Request {
        val fullUrl = if (chapter.url.startsWith("http")) chapter.url else "$baseUrl${chapter.url}"
        return GET(fullUrl, headersBuilder().add("Referer", fullUrl).build())
    }

    public override fun pageListParse(response: Response): List<Page> {
        return response.use {
            val document = it.asJsoup()
            parsePageListFromDocument(document)
        }
    }

    fun parsePageListFromDocument(document: Document): List<Page> {
        checkMangaStarzOrigin(document)

        val imgElements = document.select(".reading-content img, .page-break img, #readerarea img")

        val pages = mutableListOf<Page>()
        var index = 0
        for (img in imgElements) {
            val src = extractImageUrl(img)
            if (!src.isNullOrBlank() && !src.contains("logo") && !src.contains("banner")) {
                pages.add(Page(index++, "", src.trim()))
            }
        }
        if (pages.isEmpty()) {
            throw IOException("Manga Starz page list is empty or protected")
        }
        return pages
    }

    private fun extractImageUrl(img: Element?): String? {
        if (img == null) return null
        val src = img.attr("abs:data-src")
            .ifBlank { img.attr("abs:data-lazy-src") }
            .ifBlank { img.attr("abs:src") }
            .ifBlank { img.attr("data-src") }
            .ifBlank { img.attr("data-lazy-src") }
            .ifBlank { img.attr("src") }

        if (src.isBlank()) {
            val srcset = img.attr("srcset").ifBlank { img.attr("data-srcset") }
            if (srcset.isNotBlank()) {
                val firstUrl = srcset.split(",").firstOrNull()?.trim()?.split(" ")?.firstOrNull()
                if (!firstUrl.isNullOrBlank()) {
                    return if (firstUrl.startsWith("http")) firstUrl else "$baseUrl$firstUrl"
                }
            }
        }
        return src.trim().takeIf { it.isNotBlank() }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")
}
