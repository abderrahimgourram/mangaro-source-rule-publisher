package eu.kanade.tachiyomi.source.internal.mangaswat

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.internal.util.jsonArrayOrNull
import eu.kanade.tachiyomi.source.internal.util.jsonObjectOrNull
import eu.kanade.tachiyomi.source.internal.util.jsonPrimitiveOrNull
import eu.kanade.tachiyomi.source.internal.util.requireObject
import eu.kanade.tachiyomi.source.model.ChapterFetchCompleteness
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.time.Instant

/** Pinned Keiyoushi 60bc15ec / MangaSwat 1.6.61, versionId 2. */
class MangaSwat(private val customClient: OkHttpClient? = null) : HttpSource() {
    override val name = "MangaSwat"
    override val lang = "ar"
    override val versionId = 2
    override val baseUrl = "https://meshmanga.com"
    override val supportsLatest = true
    override val client: OkHttpClient by lazy { customClient ?: network.client.newBuilder().rateLimit(1).build() }
    private val apiUrl get() = "$baseUrl/v2/api/v2"
    private val json = Json { ignoreUnknownKeys = true }

    public override fun popularMangaRequest(page: Int): Request = GET("$apiUrl/series/?order_by=-followers_count&page=$page", headers)
    public override fun latestUpdatesRequest(page: Int): Request = GET("$apiUrl/series/releases/?page=$page", headers)
    public override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = GET(
        "$apiUrl/series/".toHttpUrl().newBuilder().addQueryParameter("search", query)
            .addQueryParameter("page", page.toString()).build().toString(), headers,
    )
    override fun getFilterList() = FilterList()
    override suspend fun getPopularManga(page: Int) = client.newCall(popularMangaRequest(page)).awaitSuccess().use { popularMangaParse(it) }
    override suspend fun getLatestUpdates(page: Int) = client.newCall(latestUpdatesRequest(page)).awaitSuccess().use { latestUpdatesParse(it) }
    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList) =
        client.newCall(searchMangaRequest(page, query, filters)).awaitSuccess().use { searchMangaParse(it) }
    override fun popularMangaParse(response: Response) = parseCatalogue(response.body.string())
    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)
    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    private fun objectResponse(body: String): JsonObject = try {
        json.parseToJsonElement(body).requireObject("MangaSwat response").also {
            if ("error" in it || "detail" in it) throw IOException("MangaSwat API error response")
        }
    } catch (e: SerializationException) {
        throw IOException("MangaSwat returned invalid JSON or a challenge page", e)
    }
    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitiveOrNull?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.requiredText(key: String): String = text(key) ?: throw IOException("MangaSwat missing $key")
    private fun JsonObject.results() = this["results"]?.jsonArrayOrNull ?: throw IOException("MangaSwat missing results array")
    private fun JsonObject.nextPage(): String? {
        if ("next" !in this) throw IOException("MangaSwat missing pagination state")
        if (this["next"] == JsonNull) return null
        val url = requiredText("next").toHttpUrl()
        if (url.scheme != "https" || url.host !in setOf("meshmanga.com", "appswat.com") || !url.encodedPath.startsWith("/v2/api/v2/")) {
            throw IOException("MangaSwat invalid continuation URL")
        }
        return url.toString()
    }
    fun parseCatalogue(body: String): MangasPage {
        val root = objectResponse(body)
        val mangas = root.results().map { element ->
            val obj = element.requireObject("MangaSwat catalogue entry")
            SManga.create().apply {
                url = obj.text("id") ?: obj.requiredText("serie_id")
                title = obj.requiredText("title")
                thumbnail_url = obj["poster"]?.jsonObjectOrNull?.text("medium")
            }
        }
        val next = root.nextPage()
        if (next != null && mangas.isEmpty()) throw IOException("MangaSwat empty continuing catalogue")
        if (mangas.distinctBy { it.url }.size != mangas.size) throw IOException("MangaSwat duplicate catalogue identities")
        return MangasPage(mangas, next != null)
    }
    override fun getMangaUrl(manga: SManga) = "$baseUrl/series/${manga.url}"
    override fun getChapterUrl(chapter: SChapter) = "$baseUrl/chapter/${chapterId(chapter)}"
    private fun chapterId(chapter: SChapter): String = chapter.url.removePrefix("/chapters/").substringBefore("/")
        .takeIf { it.toLongOrNull() != null } ?: throw IOException("MangaSwat chapter missing remote ID")

    fun parseDetails(body: String, manga: SManga): SManga {
        val obj = objectResponse(body)
        val title = obj.requiredText("title")
        val returnedId = obj.text("id")
        if (returnedId != null && returnedId != manga.url) throw IOException("MangaSwat details identity mismatch")
        return manga.apply {
            this.title = title
            obj["poster"]?.jsonObjectOrNull?.text("medium")?.let { thumbnail_url = it }
            description = obj.text("story") ?: description
            author = obj["author"]?.jsonObjectOrNull?.text("name") ?: author
            artist = obj["artist"]?.jsonObjectOrNull?.text("name") ?: artist
            obj["genres"]?.jsonArrayOrNull?.mapNotNull { it.jsonObjectOrNull?.text("name") }
                ?.joinToString(", ")?.takeIf { it.isNotBlank() }?.let { genre = it }
            status = when (obj["status"]?.jsonObjectOrNull?.text("name")) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                else -> status
            }
            initialized = true
        }
    }
    fun parseChapterBatch(body: String): ChapterBatch {
        val root = objectResponse(body)
        val chapters = root.results().map { element ->
            val obj = element.requireObject("MangaSwat chapter")
            val id = obj.requiredText("id").toIntOrNull() ?: throw IOException("MangaSwat invalid chapter ID")
            val slug = obj.requiredText("slug")
            SChapter.create().apply {
                name = obj.requiredText("chapter")
                url = "/chapters/$id/$slug/"
                memo = buildJsonObject { put("id", id); put("slug", slug) }
                date_upload = obj.text("created_at")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
            }
        }
        val count = root["count"]?.jsonPrimitiveOrNull?.intOrNull
        if (count != null && count < 0) throw IOException("MangaSwat invalid chapter count")
        return ChapterBatch(chapters, root.nextPage(), count)
    }
    data class ChapterBatch(val chapters: List<SChapter>, val next: String?, val count: Int?)

    override suspend fun getMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean): SMangaUpdate = withTimeoutOrNull(180_000) {
        if (manga.url.toLongOrNull() == null) throw IOException("MangaSwat manga missing remote ID")
        val updated = if (fetchDetails) client.newCall(GET("$apiUrl/series/${manga.url}/", headers)).awaitSuccess()
            .use { parseDetails(it.body.string(), manga) } else manga
        if (!fetchChapters) return@withTimeoutOrNull SMangaUpdate(updated, chapters, ChapterFetchCompleteness.DEGRADED)
        var next: String? = "$apiUrl/chapters/?serie=${manga.url}&order_by=-order&page_size=200"
        val visited = mutableSetOf<String>()
        val identities = mutableSetOf<String>()
        val all = mutableListOf<SChapter>()
        var total: Int? = null
        var everyCountVerified = true
        while (next != null) {
            if (!visited.add(next) || visited.size > 100) throw IOException("MangaSwat repeated or excessive chapter pagination")
            val requestUrl = next.toHttpUrl()
            if (requestUrl.queryParameter("serie") != manga.url) throw IOException("MangaSwat chapter continuation lost series identity")
            val batch = client.newCall(GET(next, headers)).awaitSuccess().use { parseChapterBatch(it.body.string()) }
            if (batch.count == null) everyCountVerified = false
            if (total != null && batch.count != null && total != batch.count) throw IOException("MangaSwat chapter count changed during retrieval")
            total = batch.count ?: total
            if (batch.next != null && batch.chapters.isEmpty()) throw IOException("MangaSwat empty continuing chapter page")
            batch.chapters.forEach {
                // Remote ID is authoritative even if a page repeats it under a different slug.
                if (!identities.add(chapterId(it))) throw IOException("MangaSwat repeated chapter identity")
                all.add(it)
            }
            next = batch.next
        }
        if (total != null && total != all.size) throw IOException("MangaSwat incomplete chapters: expected $total, received ${all.size}")
        if (all.isEmpty()) throw IOException("MangaSwat returned no chapters")
        SMangaUpdate(updated, all, if (everyCountVerified && total == all.size) ChapterFetchCompleteness.COMPLETE else ChapterFetchCompleteness.DEGRADED)
    } ?: throw IOException("MangaSwat refresh timed out; existing chapters preserved")
    override suspend fun getPageList(chapter: SChapter): List<Page> = client.newCall(GET("$apiUrl/chapters/${chapterId(chapter)}/", headers))
        .awaitSuccess().use { parsePages(it.body.string()) }
    fun parsePages(body: String): List<Page> {
        val images = objectResponse(body)["images"]?.jsonArrayOrNull ?: throw IOException("MangaSwat missing images array")
        if (images.isEmpty()) throw IOException("MangaSwat returned no pages (possibly locked chapter)")
        val expected = objectResponse(body)["images_count"]?.jsonPrimitiveOrNull?.intOrNull
        if (expected != null && expected != images.size) throw IOException("MangaSwat incomplete page list")
        return images.mapIndexed { index, element ->
            val image = element.requireObject("MangaSwat page").requiredText("image").toHttpUrl()
            if (image.scheme != "https") throw IOException("MangaSwat invalid image URL")
            Page(index, imageUrl = image.toString())
        }
    }
}
