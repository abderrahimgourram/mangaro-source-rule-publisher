package eu.kanade.tachiyomi.source.internal.azora

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.ChapterFetchCompleteness
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Filter
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import eu.kanade.tachiyomi.source.internal.util.jsonArrayOrNull
import eu.kanade.tachiyomi.source.internal.util.jsonObjectOrNull
import eu.kanade.tachiyomi.source.internal.util.jsonPrimitiveOrNull
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import eu.kanade.tachiyomi.source.internal.util.requireObject
import java.io.IOException
import java.net.URLEncoder

class Azora(
    private val customClient: OkHttpClient? = null,
) : HttpSource() {

    override val name: String = "Azora"

    override val lang: String = "ar"

    override val versionId: Int = 2

    override val baseUrl: String = "https://api.azorafly.com"

    override val supportsLatest: Boolean = true

    override val id: Long by lazy { generateId(name, lang, versionId) }

    override val client: OkHttpClient get() = customClient ?: network.client

    private val json: Json by lazy { Injekt.get() }

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "https://azora.moe/")

    override suspend fun getPopularManga(page: Int): MangasPage {
        val request = popularMangaRequest(page)
        val response = client.newCall(request).awaitSuccess()
        return popularMangaParse(response)
    }

    override fun popularMangaRequest(page: Int): Request {
        return GET(queryUrl(page, "", "totalViews"), headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        return response.use { parsePostsResponse(it.body.string(), it.request.url.queryParameter("page")?.toIntOrNull() ?: 1) }
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val request = latestUpdatesRequest(page)
        val response = client.newCall(request).awaitSuccess()
        return latestUpdatesParse(response)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET(queryUrl(page, "", "lastChapterAddedAt"), headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        return response.use { parsePostsResponse(it.body.string(), it.request.url.queryParameter("page")?.toIntOrNull() ?: 1) }
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val request = searchMangaRequest(page, query, filters)
        val response = client.newCall(request).awaitSuccess()
        return searchMangaParse(response)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val order = filters.filterIsInstance<OrderFilter>().firstOrNull()?.selected ?: "lastChapterAddedAt"
        return GET(queryUrl(page, query.trim(), order), headers)
    }

    private fun queryUrl(page: Int, query: String, order: String): String = "$baseUrl/api/query".toHttpUrl().newBuilder()
        .addQueryParameter("page", page.toString())
        .addQueryParameter("perPage", "24")
        .addQueryParameter("searchTerm", query)
        .addQueryParameter("orderBy", order)
        .addQueryParameter("orderDirection", "desc")
        .build().toString()

    override fun searchMangaParse(response: Response): MangasPage {
        return response.use { parsePostsResponse(it.body.string(), it.request.url.queryParameter("page")?.toIntOrNull() ?: 1) }
    }

    override fun getFilterList(): FilterList = FilterList(OrderFilter())

    class OrderFilter : Filter.Select<String>("الترتيب", arrayOf("آخر فصل", "الأكثر مشاهدة", "تاريخ الإضافة", "العنوان")) {
        val selected: String get() = arrayOf("lastChapterAddedAt", "totalViews", "createdAt", "postTitle")[state]
    }

    private companion object {
        val IMAGE_SERIES_TYPES = setOf("MANGA", "MANHWA", "MANHUA")
    }

    fun parsePostsResponse(responseBody: String, page: Int = 1): MangasPage {
        val jsonElement = json.parseToJsonElement(responseBody)
        val rootObj = (jsonElement as? JsonObject) ?: throw IOException("Azora catalogue is not an object")

        val postsArray = rootObj["posts"]?.jsonArrayOrNull
            ?: rootObj["data"]?.jsonArrayOrNull
            ?: (if (jsonElement is JsonArray) jsonElement else null)
            ?: throw IOException("Azora catalogue missing posts")

        val mangas = postsArray.mapNotNull { element ->
            val obj = element.requireObject("Azora entry")
            // Catalogue records expose seriesType, not the details-only isNovel flag.
            // Fail closed for text, unknown or absent types without changing server paging.
            if (obj["seriesType"]?.jsonPrimitiveOrNull?.contentOrNull !in IMAGE_SERIES_TYPES) return@mapNotNull null
            val id = obj["id"]?.jsonPrimitiveOrNull?.contentOrNull ?: throw IOException("Azora catalogue missing ID")
            val slug = obj["postSlug"]?.jsonPrimitiveOrNull?.contentOrNull
                ?: obj["slug"]?.jsonPrimitiveOrNull?.contentOrNull ?: throw IOException("Azora catalogue missing slug")
            val titleText = obj["postTitle"]?.jsonPrimitiveOrNull?.contentOrNull
                ?: obj["title"]?.jsonPrimitiveOrNull?.contentOrNull
                ?: obj["name"]?.jsonPrimitiveOrNull?.contentOrNull
                ?: slug
            val coverUrl = obj["featuredImage"]?.jsonPrimitiveOrNull?.contentOrNull
                ?: obj["featuredImageCL"]?.jsonPrimitiveOrNull?.contentOrNull
                ?: obj["cover"]?.jsonPrimitiveOrNull?.contentOrNull
                ?: obj["poster"]?.jsonPrimitiveOrNull?.contentOrNull
                ?: obj["banner"]?.jsonPrimitiveOrNull?.contentOrNull

            SManga.create().apply {
                url = "$slug#$id"
                title = titleText
                thumbnail_url = coverUrl?.let { if (it.startsWith("http")) it else "$baseUrl$it" }
            }
        }

        val total = rootObj["totalCount"]?.jsonPrimitiveOrNull?.intOrNull
        val hasMore = rootObj["hasMore"]?.jsonPrimitiveOrNull?.booleanOrNull
            ?: total?.let { page.toLong() * maxOf(24, postsArray.size) < it }
            ?: throw IOException("Azora catalogue missing pagination metadata")


        return MangasPage(mangas, hasMore)
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val postSlug = manga.url.substringBefore("#").trim('/')
        var postId = manga.url.substringAfter("#", "")

        var updatedManga = manga
        var expectedChapterCount: Int? = null
        if (fetchDetails || fetchChapters) {
            val req = GET("$baseUrl/api/post?postSlug=$postSlug", headers)
            val resp = client.newCall(req).awaitSuccess()
            val body = resp.use { it.body.string() }
            val root = json.parseToJsonElement(body).requireObject("Azora details")
            val post = root["post"]?.jsonObjectOrNull ?: throw IOException("Azora details missing post")
            if (post["isNovel"]?.jsonPrimitiveOrNull?.booleanOrNull == true) throw IOException("Azora novels are not supported by the image Reader")
            val counts = listOfNotNull(
                root["totalChapterCount"]?.jsonPrimitiveOrNull?.intOrNull,
                post["totalChapterCount"]?.jsonPrimitiveOrNull?.intOrNull,
                post["_count"]?.jsonObjectOrNull?.get("chapters")?.jsonPrimitiveOrNull?.intOrNull,
            )
            if (counts.any { it < 0 } || counts.toSet().size > 1) throw IOException("Azora inconsistent details chapter counts")
            expectedChapterCount = counts.firstOrNull()
            updatedManga = parsePostDetailsResponse(body, manga, postSlug, postId)
            postId = updatedManga.url.substringAfter("#", postId)
        }

        var updatedChapters = chapters
        if (fetchChapters) {
            if (postId.isNotEmpty()) {
                val req = GET("$baseUrl/api/chapters?postId=$postId", headers)
                val resp = client.newCall(req).awaitSuccess()
                val body = resp.use { it.body.string() }
                val chapterRoot = json.parseToJsonElement(body).requireObject("Azora chapters")
                val chapterCount = chapterRoot["totalChapterCount"]?.jsonPrimitiveOrNull?.intOrNull
                if (chapterCount != null && (chapterCount < 0 || expectedChapterCount != null && chapterCount != expectedChapterCount)) {
                    throw IOException("Azora inconsistent chapter counts: details=$expectedChapterCount chapters=$chapterCount")
                }
                // A zero is verified only by agreement between the independent details and chapter endpoints.
                if (chapterCount == 0 && expectedChapterCount != 0) throw IOException("Azora unconfirmed empty chapter list")
                updatedChapters = parseChaptersResponse(body, postSlug, postId)
                expectedChapterCount = chapterCount ?: expectedChapterCount
            } else {
                throw IOException("Unable to determine post ID for Azora manga $postSlug")
            }
        }

        if (fetchChapters && expectedChapterCount != null && updatedChapters.size != expectedChapterCount) {
            throw IOException("Azora incomplete chapters: expected $expectedChapterCount, received ${updatedChapters.size}")
        }
        return SMangaUpdate(updatedManga, updatedChapters,
            if (fetchChapters && expectedChapterCount == updatedChapters.size) ChapterFetchCompleteness.COMPLETE else ChapterFetchCompleteness.DEGRADED,
        )
    }

    fun parsePostDetailsResponse(responseBody: String, manga: SManga, postSlug: String, existingPostId: String): SManga {
        val rootObj = json.parseToJsonElement(responseBody).requireObject("Azora response")
        val postObj = rootObj["post"]?.jsonObjectOrNull
            ?: rootObj["data"]?.jsonObjectOrNull
            ?: rootObj.takeUnless { "post" in it || "data" in it || "chapter" in it }
            ?: throw IOException("Azora response missing payload")

        val realPostId = postObj["id"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: rootObj["post"]?.jsonObjectOrNull?.get("id")?.jsonPrimitiveOrNull?.contentOrNull
            ?: existingPostId

        val titleText = postObj["postTitle"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: postObj["title"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: postObj["name"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: manga.title
        val rawDescription = postObj["postContent"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: postObj["description"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: postObj["summary"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: manga.description
        val cleanDescription = rawDescription?.replace(Regex("<[^>]*>"), "")?.trim() ?: ""

        val coverUrl = postObj["featuredImage"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: postObj["featuredImageCL"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: postObj["cover"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: postObj["poster"]?.jsonPrimitiveOrNull?.contentOrNull
        val statusText = postObj["seriesStatus"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: postObj["status"]?.jsonPrimitiveOrNull?.contentOrNull

        val authorName = postObj["author"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: postObj["createdby"]?.jsonObjectOrNull?.get("name")?.jsonPrimitiveOrNull?.contentOrNull

        val artistName = postObj["artist"]?.jsonPrimitiveOrNull?.contentOrNull

        val genresArray = postObj["genres"]?.jsonArrayOrNull
        val genresText = genresArray?.mapNotNull {
            it.jsonObjectOrNull?.get("name")?.jsonPrimitiveOrNull?.contentOrNull
        }?.joinToString(", ")?.ifBlank { null }

        return manga.apply {
            url = "$postSlug#$realPostId"
            title = titleText
            description = cleanDescription
            author = authorName?.ifBlank { null }
            artist = artistName?.ifBlank { null }
            genre = genresText
            coverUrl?.let { thumbnail_url = if (it.startsWith("http")) it else "$baseUrl$it" }
            status = when {
                statusText?.contains("ONGOING", ignoreCase = true) == true || statusText?.contains("مستمر") == true -> SManga.ONGOING
                statusText?.contains("COMPLETED", ignoreCase = true) == true || statusText?.contains("مكتمل") == true -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            initialized = true
        }
    }

    fun parseChaptersResponse(responseBody: String, postSlug: String, expectedPostId: String? = null): List<SChapter> {
        val rootElement = json.parseToJsonElement(responseBody)
        val rootObj = if (rootElement is JsonObject) rootElement else null
        if (rootObj?.get("error")?.let { it != kotlinx.serialization.json.JsonNull } == true) throw IOException("Azora chapters API error")
        if (rootObj?.get("hasMore")?.jsonPrimitiveOrNull?.booleanOrNull == true ||
            rootObj?.get("nextCursor")?.jsonPrimitiveOrNull?.contentOrNull?.isNotBlank() == true
        ) throw IOException("Azora chapters response requires additional pages")
        val chaptersArray = rootObj?.get("post")?.jsonObjectOrNull?.get("chapters")?.jsonArrayOrNull
            ?: rootObj?.get("chapters")?.jsonArrayOrNull
            ?: rootObj?.get("data")?.jsonArrayOrNull
            ?: (if (rootElement is JsonArray) rootElement else null)
            ?: throw IOException("Azora chapters missing array")

        val declaredCount = rootObj?.get("totalChapterCount")?.jsonPrimitiveOrNull?.intOrNull
        if (chaptersArray.isEmpty() && declaredCount != 0) throw IOException("Azora unconfirmed empty chapter list")
        val remoteIds = HashSet<String>()
        val parsed = chaptersArray.map { element ->
            val obj = element.requireObject("Azora entry")
            val chapterId = obj["id"]?.jsonPrimitiveOrNull?.contentOrNull ?: throw IOException("Azora chapter missing remote ID")
            if (chapterId.isBlank()) throw IOException("Azora chapter missing remote ID")
            if (!remoteIds.add(chapterId)) throw IOException("Azora duplicate remote chapter ID: $chapterId")
            val rowPostId = obj["mangaPostId"]?.jsonPrimitiveOrNull?.contentOrNull
            if (expectedPostId != null && rowPostId != null && rowPostId != expectedPostId) throw IOException("Azora chapter belongs to another post")
            val chapterSlug = obj["slug"]?.jsonPrimitiveOrNull?.contentOrNull
                ?: obj["chapterSlug"]?.jsonPrimitiveOrNull?.contentOrNull ?: "chapter-$chapterId"
            val rawName = obj["name"]?.jsonPrimitiveOrNull?.contentOrNull
            val rawTitle = obj["title"]?.jsonPrimitiveOrNull?.contentOrNull
            val number = obj["number"]?.jsonPrimitiveOrNull?.doubleOrNull?.toFloat()
                ?: obj["number"]?.jsonPrimitiveOrNull?.intOrNull?.toFloat()
                ?: parseChapterNumber(chapterSlug, rawName ?: rawTitle ?: "")

            val numberStr = if (number % 1.0f == 0f) number.toInt().toString() else number.toString()

            val titleText = (rawName?.ifBlank { null } ?: rawTitle?.ifBlank { null })
            val formattedName = if (!titleText.isNullOrBlank()) {
                if (titleText.contains("الفصل") || titleText.contains("Chapter") || titleText.contains("chapter")) {
                    titleText
                } else {
                    "الفصل $numberStr: $titleText"
                }
            } else {
                "الفصل $numberStr"
            }

            SChapter.create().apply {
                url = "/series/$postSlug/$chapterSlug#$chapterId"
                name = formattedName
                chapter_number = number
                date_upload = obj["createdAt"]?.jsonPrimitiveOrNull?.contentOrNull?.let {
                    runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull()
                } ?: 0L
                scanlator = obj["scanlator"]?.jsonPrimitiveOrNull?.contentOrNull
                memo = buildJsonObject { put("azora.id", chapterId); put("id", chapterId); put("slug", chapterSlug) }
            }
        }.sortedByDescending { it.chapter_number }
        if (parsed.map { it.url }.toSet().size != parsed.size) throw IOException("Azora duplicate chapter URL")
        if (declaredCount != null && declaredCount != parsed.size) throw IOException("Azora incomplete chapters: expected $declaredCount, received ${parsed.size}")
        return parsed
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterId = chapter.url.substringAfter("#", "")
        if (chapterId.isEmpty()) throw IOException("Azora chapter missing remote ID; refresh chapters")

        val req = GET("$baseUrl/api/chapter?chapterId=$chapterId", headers)
        val response = client.newCall(req).awaitSuccess()
        val pages = parseChapterPagesResponse(response.body.string())
        if (pages.isEmpty()) {
            throw IOException("No pages returned for Azora chapter ${chapter.name}")
        }
        return pages
    }

    fun parseChapterPagesResponse(responseBody: String): List<Page> {
        val rootObj = json.parseToJsonElement(responseBody).requireObject("Azora response")
        val chapterObj = rootObj["chapter"]?.jsonObjectOrNull
            ?: rootObj["data"]?.jsonObjectOrNull
            ?: rootObj.takeUnless { "post" in it || "data" in it || "chapter" in it }
            ?: throw IOException("Azora response missing payload")

        val isLocked = chapterObj["isLocked"]?.jsonPrimitiveOrNull?.booleanOrNull == true
        if (isLocked) {
            throw IOException("Azora chapter is locked")
        }

        val pagesArray = chapterObj["images"]?.jsonArrayOrNull
            ?: chapterObj["pages"]?.jsonArrayOrNull
            ?: chapterObj["data"]?.jsonArrayOrNull
            ?: throw IOException("Azora pages missing array")

        if (pagesArray.isEmpty()) throw IOException("Azora returned no pages")
        return pagesArray.sortedBy { (it as? JsonObject)?.get("order")?.jsonPrimitiveOrNull?.intOrNull ?: Int.MAX_VALUE }.mapIndexedNotNull { index, element ->
            val pageObj = if (element is JsonObject) element.requireObject("Azora entry") else null
            val pageUrl = pageObj?.get("url")?.jsonPrimitiveOrNull?.contentOrNull
                ?: pageObj?.get("pageUrl")?.jsonPrimitiveOrNull?.contentOrNull
                ?: pageObj?.get("image")?.jsonPrimitiveOrNull?.contentOrNull
                ?: (if (element is JsonPrimitive) element.jsonPrimitiveOrNull?.contentOrNull else null)
            if (pageUrl.isNullOrBlank()) throw IOException("Azora returned an incomplete page list") else Page(index, "", if (pageUrl.startsWith("http")) pageUrl else "$baseUrl$pageUrl")
        }
    }

    private fun parseChapterNumber(slug: String, name: String): Float {
        val text = "$slug $name"
        val regex = Regex("""(?i)(?:chapter|ch|فصل|الفصل)\s*[-:]?\s*(\d+(?:\.\d+)?)""")
        val match = regex.find(text)
        if (match != null) {
            return match.groupValues[1].toFloatOrNull() ?: -1f
        }
        val numberRegex = Regex("""(\d+(?:\.\d+)?)""")
        val numMatch = numberRegex.find(slug) ?: numberRegex.find(name)
        return numMatch?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
    }
}
