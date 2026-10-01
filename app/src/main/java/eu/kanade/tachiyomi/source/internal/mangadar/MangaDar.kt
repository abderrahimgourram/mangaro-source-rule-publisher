package eu.kanade.tachiyomi.source.internal.mangadar

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.MangaDarPageResolver
import eu.kanade.tachiyomi.source.internal.util.SourceValidationUtil
import eu.kanade.tachiyomi.source.model.ChapterFetchCompleteness
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Filter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URI

class MangaDar(
    private val customClient: OkHttpClient? = null,
) : HttpSource() {

    override val name: String = "MangaDar"

    override val lang: String = "ar"

    override val versionId: Int = 1

    override val baseUrl: String = "https://mangadar.com"

    override val supportsLatest: Boolean = true

    override val id: Long by lazy { generateId(name, lang, versionId) }

    override val client: OkHttpClient get() = customClient ?: network.client

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Referer", "$baseUrl/")
        .add("Cache-Control", "no-cache")

    override suspend fun getPopularManga(page: Int): MangasPage {
        val request = popularMangaRequest(page)
        return fetchCatalogue(request, ::popularMangaParse)
    }

    private suspend fun fetchCatalogue(request: Request, parser: (Response) -> MangasPage): MangasPage {
        val response = client.newCall(request).awaitSuccess()
        val cached = response.cacheResponse != null
        try {
            return parser(response)
        } catch (error: IOException) {
            // One fresh request only when an unusable cached representation was observed.
            // Do not retry live challenge pages or add another HTTP stack.
            if (!cached) throw error
            val fresh = request.newBuilder().header("Cache-Control", "no-cache, no-store").build()
            return parser(client.newCall(fresh).awaitSuccess())
        }
    }

    public override fun popularMangaRequest(page: Int): Request {
        return GET("${catalogueUrl(page)}?sort=popular", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        return SourceValidationUtil.parseCatalogueResponse(response, ::parseMangaListFromDocument)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val request = latestUpdatesRequest(page)
        return fetchCatalogue(request, ::latestUpdatesParse)
    }

    public override fun latestUpdatesRequest(page: Int): Request {
        return GET(catalogueUrl(page), headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        return SourceValidationUtil.parseCatalogueResponse(response, ::parseMangaListFromDocument)
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val request = searchMangaRequest(page, query, filters)
        return fetchCatalogue(request, ::searchMangaParse)
    }

    public override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = catalogueUrl(page).toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) {
                addQueryParameter("s", query.trim())
            } else {
                filters.filterIsInstance<ChoiceFilter>().forEach { filter ->
                    filter.selected.takeIf { it.isNotEmpty() }?.let { addQueryParameter(filter.key, it) }
                }
            }
        }
        return GET(url.build().toString(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    private fun catalogueUrl(page: Int): String {
        require(page > 0)
        return if (page == 1) "$baseUrl/manga/" else "$baseUrl/manga/page/$page/"
    }

    override fun getFilterList(): FilterList = FilterList(
        ChoiceFilter("الترتيب", "sort", arrayOf("الأحدث", "الأكثر مشاهدة", "التقييم", "أبجدي"), arrayOf("", "popular", "rating", "az")),
        ChoiceFilter("الحالة", "status", arrayOf("الكل", "مستمر", "مكتمل", "متوقف", "ملغية"), arrayOf("", "17", "7", "31", "82")),
    )

    class ChoiceFilter(name: String, val key: String, values: Array<String>, private val queries: Array<String>) :
        Filter.Select<String>(name, values) {
        val selected: String get() = queries[state]
    }

    fun parseMangaListFromDocument(document: Document): MangasPage {
        SourceValidationUtil.checkCloudflareOrError(document)

        val candidateAnchors = mutableListOf<Element>()

        // 1. Direct document anchors
        candidateAnchors.addAll(document.select("a[href]"))

        // 2. Anchors inside <template> elements (DOM children + parsed HTML string fragments)
        document.select("template").forEach { template ->
            candidateAnchors.addAll(template.children().select("a[href]"))
            candidateAnchors.addAll(template.select("a[href]"))
            val innerHtml = template.html()
            if (innerHtml.isNotBlank()) {
                val fragment = Jsoup.parseBodyFragment(innerHtml, baseUrl)
                candidateAnchors.addAll(fragment.select("a[href]"))
            }
        }

        // 3. Filter anchors that satisfy valid MangaDar manga detail URL identity
        val validMangaAnchors = candidateAnchors.filter { anchor ->
            val href = anchor.attr("href")
            href.isNotBlank() && isMangaDetailUrl(getRelativeUrl(href))
        }

        // 4. Map each valid anchor to an SManga object
        val mangas = validMangaAnchors.mapNotNull { anchor ->
            parseMangaFromAnchor(anchor)
        }.distinctBy { it.url }

        if (mangas.isEmpty()) {
            throw IOException("MangaDar card discovery found 0 valid manga anchors (inspected ${candidateAnchors.size} total anchors)")
        }

        val hasNextPage = document.select("a[rel=next], link[rel=next], nav a[title=الصفحة التالية]").isNotEmpty()

        return MangasPage(mangas, hasNextPage)
    }

    private fun isMangaDetailUrl(url: String): Boolean {
        val clean = url.trim().substringBefore("?").substringBefore("#").trim('/')
        val parts = clean.split('/')
        if (parts.size != 2 || parts[0] != "manga") return false
        val slug = parts[1].trim()
        return slug.isNotEmpty() && slug != "page" && slug != "category" && slug != "genres" && slug != "filter"
    }

    fun parseMangaFromAnchor(anchor: Element): SManga? {
        val href = anchor.attr("href")
        if (href.isBlank() || !isMangaDetailUrl(getRelativeUrl(href))) return null

        val relativeUrl = getRelativeUrl(href)
        val container = anchor.parent()?.takeIf { it.selectFirst("img") != null } ?: anchor

        val imgElement = anchor.selectFirst("img") ?: container.selectFirst("img")
        val titleText = anchor.attr("title").ifBlank {
            imgElement?.attr("alt")
                ?: anchor.selectFirst("h3, h2, h1, .title, .tt, div.post-title")?.text()
                ?: container.selectFirst("h3, h2, h1, .title, .tt, div.post-title")?.text()
                ?: anchor.text()
        }.trim()

        if (titleText.isBlank()) return null

        val thumbnailUrl = imgElement?.attr("abs:data-src")
            ?.ifBlank { imgElement.attr("data-src") }
            ?.ifBlank { imgElement.attr("abs:data-lazy-src") }
            ?.ifBlank { imgElement.attr("data-lazy-src") }
            ?.ifBlank { imgElement.attr("abs:src") }
            ?.ifBlank { imgElement.attr("src") }
            ?.takeIf { !it.startsWith("data:") }

        return SManga.create().apply {
            url = relativeUrl
            title = titleText
            thumbnail_url = thumbnailUrl
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
        SourceValidationUtil.checkCloudflareOrError(document)

        if (document.select("h1").isEmpty()) throw IOException("MangaDar details missing manga identity")
        val updatedManga = if (fetchDetails) parseMangaDetails(document, manga) else manga
        val updatedChapters = if (fetchChapters) {
            val parsed = parseChapters(document)
            if (parsed.isEmpty()) {
                throw IOException("MangaDar returned 0 chapters for manga ${manga.title}")
            }
            parsed
        } else {
            chapters
        }

        return SMangaUpdate(
            updatedManga,
            updatedChapters,
            if (fetchChapters && document.select("div[x-data]").any { it.attr("x-data").contains("rows:") }) {
                ChapterFetchCompleteness.COMPLETE
            } else {
                ChapterFetchCompleteness.DEGRADED
            },
        )
    }

    fun parseMangaDetails(document: Document, manga: SManga): SManga {
        return manga.apply {
            val titleText = document.selectFirst("h1.entry-title, h1.title, div.post-title h1, main h1")?.text()?.trim()
            if (!titleText.isNullOrBlank()) {
                title = titleText
            }

            val imgElement = document.selectFirst("div.thumb img, div.summary_image img, meta[property=og:image]")
            if (imgElement != null) {
                val coverUrl = imgElement.attr("content").ifBlank { imgElement.attr("abs:data-src") }
                    .ifBlank { imgElement.attr("data-src") }
                    .ifBlank { imgElement.attr("abs:src") }
                    .ifBlank { imgElement.attr("src") }
                if (coverUrl.isNotBlank() && !coverUrl.startsWith("data:")) {
                    thumbnail_url = coverUrl
                }
            }

            document.selectFirst("meta[name=description]")?.attr("content")
                ?.takeIf { it.isNotBlank() }?.let { description = it }
            document.select("div.description, div.summary__content, div.entry-content").text().trim()
                .takeIf { it.isNotBlank() }?.let { description = it }
            val text = document.text()
            status = when {
                text.contains("مستمر") || text.contains("Ongoing") -> SManga.ONGOING
                text.contains("مكتمل") || text.contains("Completed") -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            initialized = true
        }
    }

    fun parseChapters(document: Document): List<SChapter> {
        SourceValidationUtil.checkCloudflareOrError(document)
        val data = document.select("div[x-data]").map { it.attr("x-data") }
            .firstOrNull { it.contains("rows:") }
        if (data != null) {
            val start = data.indexOf('[', data.indexOf("rows:"))
            val rows = extractRows(data, start)
            val parsed = Json.parseToJsonElement(rows).jsonArray.map { element ->
                val row = element as? JsonArray ?: throw IOException("MangaDar invalid chapter row")
                if (row.size < 4) throw IOException("MangaDar incomplete chapter row")
                val id = row[0].jsonPrimitive.content
                val number = row[1].jsonPrimitive.content
                val path = getRelativeUrl(row[2].jsonPrimitive.content)
                if (!path.startsWith("/manga/") || path.trim('/').split('/').size != 3) {
                    throw IOException("MangaDar invalid chapter URL")
                }
                SChapter.create().apply {
                    url = path
                    name = "الفصل $number"
                    chapter_number = number.toFloatOrNull() ?: -1f
                    date_upload = (row[3].jsonPrimitive.longOrNull ?: throw IOException("MangaDar invalid chapter date")) * 1000
                    memo = buildJsonObject { put("mangadar.id", id) }
                }
            }
            if (parsed.isEmpty() || parsed.distinctBy { it.url }.size != parsed.size) {
                throw IOException("MangaDar empty or duplicate chapter rows")
            }
            return parsed
        }
        val elements = document.select("div.chapter-card, li.wp-manga-chapter, div.chapter-item, div#chapterlist ul li")
        return elements.mapNotNull { element ->
            val linkElement = element.selectFirst("a") ?: return@mapNotNull null
            val href = linkElement.attr("href")
            if (href.isBlank()) return@mapNotNull null

            val relativeUrl = getRelativeUrl(href)
            val chapName = element.selectFirst("span.chapter-title, span.chapternum")?.text()?.trim()
                ?: linkElement.text().trim()

            if (chapName.isBlank()) return@mapNotNull null

            val num = parseChapterNumber(chapName, relativeUrl)

            SChapter.create().apply {
                url = relativeUrl
                name = chapName
                chapter_number = num
            }
        }.sortedByDescending { it.chapter_number }
    }

    private fun extractRows(data: String, start: Int): String {
        if (start < 0) throw IOException("MangaDar chapter rows missing")
        var depth = 0
        var quoted = false
        var escaped = false
        for (i in start until data.length) {
            val ch = data[i]
            if (quoted) {
                if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') quoted = false
            } else {
                when (ch) {
                    '"' -> quoted = true
                    '[' -> depth++
                    ']' -> if (--depth == 0) return data.substring(start, i + 1)
                }
            }
        }
        throw IOException("MangaDar chapter rows truncated")
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        return MangaDarPageResolver.getPages(this, chapter)
    }

    private fun getRelativeUrl(url: String): String {
        return try {
            val uri = URI(url)
            val path = uri.rawPath
            val query = uri.rawQuery
            val fragment = uri.rawFragment

            buildString {
                append(path)
                if (query != null) append("?$query")
                if (fragment != null) append("#$fragment")
            }
        } catch (_: Exception) {
            url
        }
    }

    private fun parseChapterNumber(name: String, url: String): Float {
        val text = "$name $url"
        val regex = Regex("""(?i)(?:chapter|ch|فصل|الفصل)\s*[-:]?\s*(\d+(?:\.\d+)?)""")
        val match = regex.find(text)
        if (match != null) {
            return match.groupValues[1].toFloatOrNull() ?: -1f
        }
        val numberRegex = Regex("""(\d+(?:\.\d+)?)""")
        val numMatch = numberRegex.find(name) ?: numberRegex.find(url)
        return numMatch?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
    }
}
