package eu.kanade.tachiyomi.source.internal.mangatime

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.ChapterFetchCompleteness
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

class MangaTime(
    private val customClient: OkHttpClient? = null,
) : HttpSource() {

    override val name: String = "MangaTime"

    override val lang: String = "ar"

    override val versionId: Int = 1

    override val baseUrl: String = "https://mangatime.org"

    override val supportsLatest: Boolean = true

    override val id: Long by lazy { generateId(name, lang, versionId) }

    override val client: OkHttpClient get() = customClient ?: network.client

    private val json: Json by lazy { Injekt.get() }

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    override suspend fun getPopularManga(page: Int): MangasPage {
        val request = popularMangaRequest(page)
        val response = client.newCall(request).awaitSuccess()
        return popularMangaParse(response)
    }

    public override fun popularMangaRequest(page: Int): Request {
        val input = """{"json":{"limit":24,"page":$page,"sortBy":"popularity"}}"""
        val encoded = URLEncoder.encode(input, "UTF-8")
        return GET("$baseUrl/api/trpc/search.searchSeries?input=$encoded", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        return parseSeriesSearchResponse(response.body.string())
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val request = latestUpdatesRequest(page)
        val response = client.newCall(request).awaitSuccess()
        return latestUpdatesParse(response)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val input = """{"json":{"limit":24,"page":$page,"sortBy":"recent"}}"""
        val encoded = URLEncoder.encode(input, "UTF-8")
        return GET("$baseUrl/api/trpc/search.searchSeries?input=$encoded", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        return parseSeriesSearchResponse(response.body.string())
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val request = searchMangaRequest(page, query, filters)
        val response = client.newCall(request).awaitSuccess()
        return searchMangaParse(response)
    }

    public override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val trimmed = query.trim()
        val input = if (trimmed.isNotEmpty()) {
            """{"json":{"query":${JsonPrimitive(trimmed)},"limit":24,"page":$page,"sortBy":"popularity"}}"""
        } else {
            """{"json":{"limit":24,"page":$page,"sortBy":"popularity"}}"""
        }
        val encoded = URLEncoder.encode(input, "UTF-8")
        return GET("$baseUrl/api/trpc/search.searchSeries?input=$encoded", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        return parseSeriesSearchResponse(response.body.string())
    }

    override fun getFilterList(): FilterList = FilterList()

    fun parseSeriesSearchResponse(responseBody: String): MangasPage {
        if (responseBody.contains(""""error":""")) {
            throw IOException("MangaTime tRPC catalogue error")
        }
        val jsonElement = json.parseToJsonElement(responseBody)
        val rootObj = if (jsonElement is JsonArray) {
            jsonElement.firstOrNull()?.jsonObjectOrNull
        } else {
            jsonElement.requireObject("MangaTime response")
        } ?: throw IOException("MangaTime catalogue missing result envelope")

        val resultData = rootObj["result"]?.jsonObjectOrNull?.get("data")?.jsonObjectOrNull?.get("json")?.jsonObjectOrNull
            ?: throw IOException("MangaTime catalogue missing result envelope")

        val seriesArray = resultData["results"]?.jsonArrayOrNull ?: resultData["series"]?.jsonArrayOrNull ?: throw IOException("MangaTime catalogue missing results")
        val mangas = seriesArray.mapNotNull { element ->
            val obj = element.requireObject("MangaTime entry")
            val id = obj["id"]?.jsonPrimitiveOrNull?.contentOrNull ?: throw IOException("MangaTime catalogue missing ID")
            val slug = obj["slug"]?.jsonPrimitiveOrNull?.contentOrNull ?: throw IOException("MangaTime catalogue missing slug")
            val type = obj["type"]?.jsonPrimitiveOrNull?.contentOrNull ?: "manhwa"
            val title = obj["title"]?.jsonPrimitiveOrNull?.contentOrNull ?: slug
            val cover = obj["coverUrl"]?.jsonPrimitiveOrNull?.contentOrNull ?: obj["cover"]?.jsonPrimitiveOrNull?.contentOrNull

            SManga.create().apply {
                url = "/$type/$slug#$id"
                this.title = title
                this.thumbnail_url = cover?.let { if (it.startsWith("http")) it else "$baseUrl$it" }
            }
        }

        val hasMore = resultData["hasMore"]?.jsonPrimitiveOrNull?.booleanOrNull
            ?: (mangas.size >= 24)

        return MangasPage(mangas, hasMore)
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val (type, slug, seriesId) = parseMangaUrl(manga.url)

        var updatedManga = manga
        if (fetchDetails) {
            val input = """{"json":{"slug":"$slug","type":"$type"}}"""
            val encoded = URLEncoder.encode(input, "UTF-8")
            val req = GET("$baseUrl/api/trpc/content.getSeriesBySlug?input=$encoded", headers)
            val resp = client.newCall(req).awaitSuccess()
            val body = resp.body.string()
            if (body.contains(""""error":""")) {
                throw IOException("MangaTime tRPC details error for $slug")
            }
            updatedManga = parseMangaDetailsResponse(body, manga, type, slug, seriesId)
        }

        var updatedChapters = chapters
        var completeness = ChapterFetchCompleteness.DEGRADED
        if (fetchChapters) {
            val effectiveSeriesId = seriesId.ifEmpty {
                parseSeriesIdFromUrl(updatedManga.url)
            }
            if (effectiveSeriesId.isEmpty()) throw IOException("MangaTime manga missing series ID; refresh details")
            if (effectiveSeriesId.isNotEmpty()) {
                val input = """{"json":{"seriesId":"$effectiveSeriesId","limit":-1}}"""
                val encoded = URLEncoder.encode(input, "UTF-8")
                val req = GET("$baseUrl/api/trpc/content.getChapters?input=$encoded", headers)
                val resp = client.newCall(req).awaitSuccess()
                val body = resp.body.string()
                if (body.contains(""""error":""")) {
                    throw IOException("MangaTime tRPC chapters error for series $effectiveSeriesId")
                }
                updatedChapters = parseChaptersResponse(body, type, slug)
                val envelope = json.parseToJsonElement(body)
                val root = if (envelope is JsonArray) envelope.firstOrNull()?.jsonObjectOrNull ?: throw IOException("MangaTime empty envelope") else envelope.requireObject("MangaTime response")
                val data = root["result"]?.jsonObjectOrNull?.get("data")?.jsonObjectOrNull?.get("json")?.jsonObjectOrNull
                    ?: throw IOException("MangaTime chapters missing result")
                if (data["hasMore"]?.jsonPrimitiveOrNull?.booleanOrNull == true || !data["nextCursor"]?.jsonPrimitiveOrNull?.contentOrNull.isNullOrEmpty()) {
                    throw IOException("MangaTime unlimited chapter request returned a partial result")
                }
                if (data["hasMore"]?.jsonPrimitiveOrNull?.booleanOrNull == false && "nextCursor" in data) {
                    completeness = ChapterFetchCompleteness.COMPLETE
                }
            }
        }

        return SMangaUpdate(updatedManga, updatedChapters, completeness)
    }

    fun parseMangaDetailsResponse(
        responseBody: String,
        manga: SManga,
        type: String,
        slug: String,
        existingSeriesId: String,
    ): SManga {
        val jsonElement = json.parseToJsonElement(responseBody)
        val rootObj = if (jsonElement is JsonArray) jsonElement.firstOrNull()?.jsonObjectOrNull else jsonElement.requireObject("MangaTime response")
        val seriesData = rootObj?.get("result")?.jsonObjectOrNull?.get("data")?.jsonObjectOrNull?.get("json")?.jsonObjectOrNull
            ?: rootObj?.get("result")?.jsonObjectOrNull?.get("data")?.jsonObjectOrNull?.takeUnless { "json" in it }
            ?: throw IOException("MangaTime details missing result")

        val titleText = seriesData?.get("title")?.jsonPrimitiveOrNull?.contentOrNull ?: manga.title
        val descriptionText = seriesData?.get("description")?.jsonPrimitiveOrNull?.contentOrNull ?: manga.description
        val coverUrl = seriesData?.get("coverUrl")?.jsonPrimitiveOrNull?.contentOrNull
            ?: seriesData?.get("cover")?.jsonPrimitiveOrNull?.contentOrNull
        val realSeriesId = seriesData?.get("id")?.jsonPrimitiveOrNull?.contentOrNull ?: existingSeriesId
        val statusText = seriesData?.get("status")?.jsonPrimitiveOrNull?.contentOrNull

        return manga.apply {
            url = "/$type/$slug#$realSeriesId"
            title = titleText
            description = descriptionText
            coverUrl?.let { thumbnail_url = if (it.startsWith("http")) it else "$baseUrl$it" }
            status = when {
                statusText?.contains("ONGOING", ignoreCase = true) == true || statusText?.contains("مستمر") == true -> SManga.ONGOING
                statusText?.contains("COMPLETED", ignoreCase = true) == true || statusText?.contains("مكتمل") == true -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            initialized = true
        }
    }

    fun parseChaptersResponse(responseBody: String, type: String, slug: String): List<SChapter> {
        val jsonElement = json.parseToJsonElement(responseBody)
        val rootObj = if (jsonElement is JsonArray) jsonElement.firstOrNull()?.jsonObjectOrNull else jsonElement.requireObject("MangaTime response")
        val chaptersArray = rootObj?.get("result")?.jsonObjectOrNull?.get("data")?.jsonObjectOrNull?.get("json")?.jsonObjectOrNull?.get("chapters")?.jsonArrayOrNull
            ?: rootObj?.get("result")?.jsonObjectOrNull?.get("data")?.jsonObjectOrNull?.takeUnless { "json" in it }?.get("chapters")?.jsonArrayOrNull
            ?: throw IOException("MangaTime missing chapters array")

        val parsed = chaptersArray.map { element ->
            val obj = element.requireObject("MangaTime entry")
            val number = obj["number"]?.jsonPrimitiveOrNull?.doubleOrNull
                ?: obj["number"]?.jsonPrimitiveOrNull?.intOrNull?.toDouble()
                ?: obj["number"]?.jsonPrimitiveOrNull?.contentOrNull?.toDoubleOrNull()
                ?: throw IOException("MangaTime chapter missing number")
            val numberStr = if (number % 1.0 == 0.0) number.toInt().toString() else number.toString()
            val chapterTitle = obj["title"]?.jsonPrimitiveOrNull?.contentOrNull

            val formattedName = if (!chapterTitle.isNullOrBlank()) {
                if (chapterTitle.contains("الفصل") || chapterTitle.contains("Chapter")) {
                    chapterTitle
                } else {
                    "الفصل $numberStr: $chapterTitle"
                }
            } else {
                "الفصل $numberStr"
            }

            SChapter.create().apply {
                url = "/$type/$slug/chapter/$numberStr"
                name = formattedName
                chapter_number = number.toFloat()
                memo = kotlinx.serialization.json.buildJsonObject {
                    obj["id"]?.jsonPrimitiveOrNull?.contentOrNull?.let { put("mangatime.id", it) }
                }
            }
        }.sortedByDescending { it.chapter_number }
        if (parsed.isEmpty() || parsed.distinctBy { it.url }.size != parsed.size) throw IOException("MangaTime empty or ambiguous chapter list")
        return parsed
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val (type, slug, chapterNumberStr) = parseChapterUrl(chapter.url)
        val doubleNum = chapterNumberStr.toDoubleOrNull()
        val jsonNumVal = if (doubleNum != null && doubleNum % 1.0 != 0.0) "$doubleNum" else "${doubleNum?.toInt() ?: chapterNumberStr.toIntOrNull() ?: 1}"

        val input = """{"json":{"seriesSlug":"$slug","chapterNumber":$jsonNumVal}}"""
        val encoded = URLEncoder.encode(input, "UTF-8")
        val req = GET("$baseUrl/api/trpc/content.getChapterPages?input=$encoded", headers)
        val response = client.newCall(req).awaitSuccess()
        val responseBody = response.body.string()
        if (responseBody.contains(""""error":""")) {
            throw IOException("MangaTime tRPC error for $slug chapter $chapterNumberStr")
        }
        return parseChapterPagesResponse(responseBody)
    }

    fun parseChapterPagesResponse(responseBody: String): List<Page> {
        val jsonElement = json.parseToJsonElement(responseBody)
        val rootObj = if (jsonElement is JsonArray) jsonElement.firstOrNull()?.jsonObjectOrNull else jsonElement.requireObject("MangaTime response")
        val dataObj = rootObj?.get("result")?.jsonObjectOrNull?.get("data")?.jsonObjectOrNull?.get("json")?.jsonObjectOrNull
            ?: throw IOException("MangaTime pages missing result envelope")

        val pagesArray = dataObj["pages"]?.jsonArrayOrNull ?: throw IOException("MangaTime pages missing array")
        if (pagesArray.isEmpty()) throw IOException("MangaTime returned no pages")
        return pagesArray.mapIndexedNotNull { index, element ->
            val imgUrl = element.jsonPrimitiveOrNull?.contentOrNull ?: throw IOException("MangaTime page missing image")
            if (imgUrl.isBlank()) throw IOException("MangaTime returned an incomplete page list") else Page(index, "", if (imgUrl.startsWith("http")) imgUrl else "$baseUrl$imgUrl")
        }
    }

    private fun parseMangaUrl(url: String): Triple<String, String, String> {
        val cleanUrl = url.trim()
        val fragment = cleanUrl.substringAfter("#", "")
        val path = cleanUrl.substringBefore("#").trim('/')
        val parts = path.split('/')

        val type = if (parts.isNotEmpty()) parts[0] else "manhwa"
        val slug = if (parts.size >= 2) parts[1] else path

        return Triple(type, slug, fragment)
    }

    private fun parseSeriesIdFromUrl(url: String): String {
        return url.substringAfter("#", "")
    }

    private fun parseChapterUrl(url: String): Triple<String, String, String> {
        val cleanUrl = url.trim().substringBefore("#").trim('/')
        val parts = cleanUrl.split('/')

        val type = if (parts.isNotEmpty()) parts[0] else "manhwa"
        val slug = if (parts.size >= 2) parts[1] else ""
        val numberStr = if (parts.size >= 4) parts[3] else "1"

        return Triple(type, slug, numberStr)
    }
}
