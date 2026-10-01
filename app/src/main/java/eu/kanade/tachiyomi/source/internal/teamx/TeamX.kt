package eu.kanade.tachiyomi.source.internal.teamx

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.internal.util.SourceValidationUtil
import eu.kanade.tachiyomi.source.model.ChapterFetchCompleteness
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URI
import java.net.URLEncoder

class TeamX(
    private val customClient: OkHttpClient? = null,
) : HttpSource() {

    override val name: String = "Team X"

    override val lang: String = "ar"

    override val versionId: Int = 1

    override val baseUrl: String = "https://olympustaff.com"

    override val supportsLatest: Boolean = true

    override val id: Long by lazy { generateId(name, lang, versionId) }

    override val client: OkHttpClient get() = customClient ?: network.client

    val chapterPaginationSemaphore = Semaphore(CHAPTER_PAGINATION_CONCURRENCY_LIMIT)

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    override suspend fun getPopularManga(page: Int): MangasPage {
        val request = popularMangaRequest(page)
        val response = client.newCall(request).awaitSuccess()
        return popularMangaParse(response)
    }

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/series/?page=$page", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        return parseMangaListFromDocument(document)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val request = latestUpdatesRequest(page)
        val response = client.newCall(request).awaitSuccess()
        return latestUpdatesParse(response)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/?page=$page", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        return parseMangaListFromDocument(document)
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val request = searchMangaRequest(page, query, filters)
        val response = client.newCall(request).awaitSuccess()
        return searchMangaParse(response)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val trimmedQuery = query.trim()
        return if (trimmedQuery.isNotEmpty()) {
            val encoded = URLEncoder.encode(trimmedQuery, "UTF-8")
            GET("$baseUrl/search?keyword=$encoded&page=$page", headers)
        } else {
            GET("$baseUrl/series/?page=$page", headers)
        }
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        return parseMangaListFromDocument(document)
    }

    override fun getFilterList(): FilterList = FilterList()

    fun parseMangaListFromDocument(document: Document): MangasPage {
        SourceValidationUtil.checkCloudflareOrError(document)
        val elements = document.select("div.listupd div.bsx, a.tx-card, div.bsx, div.last-chapter div.box")
        val mangas = elements.mapNotNull { element ->
            parseMangaFromElement(element)
        }.distinctBy { it.url }

        if (mangas.isEmpty()) throw IOException("TeamX catalogue contained no validated manga cards")
        val hasNextPage = document.select("ul.pagination a[rel=next]").first() != null ||
            document.select("ul.pagination li.page-item:not(.disabled) a[href*=?page=]").any {
                it.text().trim() == ">" || it.text().trim() == "›" || it.attr("rel") == "next"
            } || document.select(".tx-pager a.tx-pager-btn[href]").any { it.text().contains("التالي") }

        return MangasPage(mangas, hasNextPage)
    }

    fun parseMangaFromElement(element: Element): SManga? {
        val linkElement = if (element.tagName() == "a") element else element.selectFirst("a[href*=/series/]") ?: element.selectFirst("a")
        val href = linkElement?.attr("href") ?: return null
        val relativeUrl = getRelativeUrl(href)
        if (!relativeUrl.startsWith("/series/")) return null

        val title = linkElement.attr("title").ifBlank {
            element.selectFirst("h3, .entry-title, .tt")?.text() ?: linkElement.selectFirst("img")?.attr("alt") ?: ""
        }.trim()

        if (title.isBlank()) return null

        val imgElement = element.selectFirst("img")
        val thumbnailUrl = imgElement?.attr("abs:src")
            ?.ifBlank { imgElement.attr("src") }
            ?.ifBlank { imgElement.attr("abs:data-src") }
            ?.ifBlank { imgElement.attr("data-src") }

        return SManga.create().apply {
            url = relativeUrl
            this.title = title
            this.thumbnail_url = thumbnailUrl
        }
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val request = GET(baseUrl + manga.url, headers)
        val response = client.newCall(request).awaitSuccess()
        val document = response.asJsoup()

        val updatedManga = if (fetchDetails) parseMangaDetails(document, manga) else manga
        val chapterResult = if (fetchChapters) fetchVerifiedChapters(document, manga.url) else Pair(chapters, ChapterFetchCompleteness.DEGRADED)

        return SMangaUpdate(updatedManga, chapterResult.first, chapterResult.second)
    }

    fun parseMangaDetails(document: Document, manga: SManga): SManga {
        return manga.apply {
            val titleText = document.selectFirst("h1, .entry-title, .post-title")?.text()?.trim()
            if (!titleText.isNullOrBlank()) {
                title = titleText.replace(" - مانجا مترجمة", "").trim()
            }

            val imgElement = document.selectFirst("div.thumb img, div.poster img, meta[property=og:image]")
            if (imgElement != null) {
                val coverUrl = if (imgElement.tagName() == "meta") {
                    imgElement.attr("content")
                } else {
                    imgElement.attr("abs:src").ifBlank { imgElement.attr("src") }
                }
                if (!coverUrl.isNullOrBlank()) {
                    thumbnail_url = coverUrl
                }
            }

            description = document.select("div.description, div.entry-content p, meta[name=description]").firstOrNull()?.let {
                if (it.tagName() == "meta") it.attr("content") else it.text()
            }?.trim()

            status = parseStatus(document.text())
            genre = document.select("a[href*=/genre/], a[href*=/genres/], .mgen a").joinToString(", ") { it.text().trim() }
            initialized = true
        }
    }

    private fun parseStatus(text: String): Int {
        return when {
            text.contains("مستمر") || text.contains("Ongoing") -> SManga.ONGOING
            text.contains("مكتمل") || text.contains("Completed") -> SManga.COMPLETED
            text.contains("متروك") || text.contains("موقف") || text.contains("On Hold") -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
    }

    suspend fun parseChapters(initialDocument: Document, mangaUrl: String): List<SChapter> = fetchVerifiedChapters(initialDocument, mangaUrl).first

    private suspend fun fetchVerifiedChapters(initialDocument: Document, mangaUrl: String): Pair<List<SChapter>, ChapterFetchCompleteness> = withTimeout(180_000) {
        coroutineScope {
            SourceValidationUtil.checkCloudflareOrError(initialDocument)
            val allChapters = verifiedChapterPage(initialDocument, mangaUrl).toMutableList()
            var allRowsMapped = allChapters.size == initialDocument.select("div.chapter-card").size
            if (allChapters.isEmpty()) throw IOException("TeamX returned no chapters for $mangaUrl")
            val completed = mutableSetOf<String>()
            val pending = getAdditionalChapterPageUrls(initialDocument, mangaUrl).toMutableSet()
            while (pending.isNotEmpty()) {
                val batch = pending.take(CHAPTER_PAGINATION_CONCURRENCY_LIMIT)
                pending.removeAll(batch.toSet())
                val documents = batch.map { path ->
                    async {
                        chapterPaginationSemaphore.withPermit {
                            client.newCall(GET(baseUrl + path, headers)).awaitSuccess().use { it.asJsoup() }
                        }
                    }
                }.awaitAll()
                for ((index, document) in documents.withIndex()) {
                    SourceValidationUtil.checkCloudflareOrError(document)
                    val parsed = verifiedChapterPage(document, mangaUrl)
                    allRowsMapped = allRowsMapped && parsed.size == document.select("div.chapter-card").size
                    val previousUrls = allChapters.map { it.url }.toHashSet()
                    if (parsed.isEmpty() || parsed.none { it.url !in previousUrls }) {
                        throw IOException("TeamX chapter page ${batch[index]} was empty or repeated")
                    }
                    allChapters.addAll(parsed)
                    completed.add(batch[index])
                    pending.addAll(getAdditionalChapterPageUrls(document, mangaUrl).filter { it !in completed })
                }
                pending.removeAll(completed)
            }
            Pair(allChapters.distinctBy { it.url }, if (allRowsMapped) ChapterFetchCompleteness.COMPLETE else ChapterFetchCompleteness.DEGRADED)
        }
    }

    private fun verifiedChapterPage(document: Document, mangaUrl: String): List<SChapter> {
        val cards = document.select("div.chapter-card")
        val accessible = cards.count {
            it.select("i.fa-lock, a[data-bs-target='#buyModel'], a[data-bs-toggle='modal']").isEmpty()
        }
        val parsed = parseChaptersFromDocument(document)
        if (parsed.size != accessible || parsed.any { !it.url.startsWith(mangaUrl.substringBefore('?').trimEnd('/') + "/") }) {
            throw IOException("TeamX chapter page contains malformed or unrelated rows")
        }
        return parsed
    }

    fun parseChaptersFromDocument(document: Document): List<SChapter> {
        val elements = document.select("div.chapter-card")
        return elements.mapNotNull { element ->
            parseChapterFromElement(element)
        }
    }

    fun parseChapterFromElement(element: Element): SChapter? {
        val lockIcon = element.selectFirst("i.fa-lock, .status-badge.unread i.fa-lock, span.status-badge i.fa-lock")
        val modalTrigger = element.selectFirst("a[data-bs-target='#buyModel'], a[data-bs-toggle='modal']")
        if (lockIcon != null || modalTrigger != null) {
            return null
        }

        val linkElement = element.selectFirst("a.chapter-link, a[href*=/series/]") ?: return null
        val href = linkElement.attr("href")
        if (href.isBlank() || href == "#") return null

        val relativeUrl = getRelativeUrl(href)
        if (!relativeUrl.startsWith("/series/")) return null

        val chapterName = element.selectFirst(".chapter-number, .chapter-title")?.text()?.trim()
            ?: linkElement.text().trim()
            ?: element.selectFirst("img")?.attr("alt")?.trim()
            ?: ""

        if (chapterName.isBlank()) return null

        val dateUpload = element.attr("data-date").toLongOrNull()?.let { it * 1000L } ?: 0L

        return SChapter.create().apply {
            url = relativeUrl
            name = chapterName
            date_upload = dateUpload
        }
    }

    fun getAdditionalChapterPageUrls(document: Document, mangaUrl: String): List<String> {
        val cleanMangaUrl = getRelativeUrl(mangaUrl).substringBefore("?")
        val pageLinks = document.select("ul.pagination a[href*=?page=]")

        val pageNumbers = pageLinks.mapNotNull { link ->
            val href = link.attr("href")
            val pageParam = href.substringAfter("page=", "").substringBefore("&")
            pageParam.toIntOrNull() ?: link.text().trim().toIntOrNull()
        }.filter { it > 1 }

        if (pageNumbers.isEmpty()) return emptyList()

        val maxPage = pageNumbers.maxOrNull() ?: return emptyList()
        if (maxPage > 500) throw IOException("TeamX chapter pagination exceeds safety bound")
        return (2..maxPage).map { pageNum -> "$cleanMangaUrl?page=$pageNum" }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val request = GET(baseUrl + chapter.url, headers)
        val response = client.newCall(request).awaitSuccess()
        return pageListParse(response)
    }

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        return parsePagesFromDocument(document)
    }

    fun parsePagesFromDocument(document: Document): List<Page> {
        SourceValidationUtil.checkCloudflareOrError(document)
        val elements = document.select("div.image_list img, div.image_list canvas, img.manga-chapter-img")
        val pages = mutableListOf<Page>()

        elements.forEach { element ->
            val url = element.attr("abs:data-src")
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("abs:src") }
                .ifBlank { element.attr("src") }

            if (url.isNotBlank() && !url.startsWith("data:") && !url.contains("btn_close")) {
                pages.add(Page(pages.size, "", url))
            }
        }

        if (pages.isEmpty()) throw IOException("TeamX returned an empty or incomplete page list")
        return pages
    }

    private fun getRelativeUrl(url: String): String {
        return if (url.startsWith("http://") || url.startsWith("https://")) {
            val uri = URI(url)
            val path = uri.rawPath
            val query = uri.rawQuery
            if (query != null) "$path?$query" else path
        } else {
            url
        }
    }

    companion object {
        const val CHAPTER_PAGINATION_CONCURRENCY_LIMIT = 2
    }
}
