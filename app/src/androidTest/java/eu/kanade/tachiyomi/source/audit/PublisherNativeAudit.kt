package eu.kanade.tachiyomi.source.audit

import android.content.Context
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.internal.azora.Azora
import eu.kanade.tachiyomi.source.internal.hijala.Hijala
import eu.kanade.tachiyomi.source.internal.mangadar.MangaDar
import eu.kanade.tachiyomi.source.internal.mangalek.MangaLek
import eu.kanade.tachiyomi.source.internal.mangatime.MangaTime
import eu.kanade.tachiyomi.source.internal.mangaswat.MangaSwat
import eu.kanade.tachiyomi.source.internal.mangastarz.MangaStarz
import eu.kanade.tachiyomi.source.internal.teamx.TeamX
import eu.kanade.tachiyomi.source.model.*
import eu.kanade.tachiyomi.source.online.HttpSource
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Publisher-only evidence; real parsers/NetworkHelper. Raw responses stay private and ephemeral.
 * No library writes, downloads, cookies/auth headers in records, or normal source registration.
 */
object PublisherNativeAudit {
    private class ProbeBudget : IOException()
    suspend fun run(context: Context, selected: String?, record: (String) -> Unit) {
        val network = Injekt.get<NetworkHelper>().client
        val results = mutableListOf<JsonObject>()
        val factories: List<Pair<String, (OkHttpClient) -> HttpSource>> = listOf(
            "TeamX" to { TeamX(it) }, "MangaTime" to { MangaTime(it) }, "MangaLek" to { MangaLek(it) },
            "Azora" to { Azora(it) }, "Hijala" to { Hijala(it) }, "MangaDar" to { MangaDar(it) }, "MangaSwat" to { MangaSwat(it) },
            "Manga Starz" to { MangaStarz(it) },
        )
        for ((name, factory) in factories.filter { selected == null || it.first == selected }) {
            val requests = AtomicInteger()
            val traces = mutableListOf<JsonObject>()
            var operation = ""
            val client = network.newBuilder().callTimeout(12, java.util.concurrent.TimeUnit.SECONDS).addInterceptor { chain ->
                if (requests.incrementAndGet() > 48) throw ProbeBudget()
                val request = chain.request()
                val response = chain.proceed(request)
                if (operation != "image") {
                    val bytes = response.peekBody(2 * 1024 * 1024L + 1).bytes()
                    if (bytes.size > 2 * 1024 * 1024) { response.close(); throw ProbeBudget() }
                    synchronized(traces) { traces += buildJsonObject {
                        put("operation", operation); put("url", request.url.toString()); put("finalUrl", response.request.url.toString())
                        put("method", request.method); put("status", response.code); put("contentType", response.header("Content-Type").orEmpty())
                        put("body", bytes.toString(Charsets.UTF_8))
                    } }
                }
                response
            }.build()
            val source = factory(client)
            val steps = linkedMapOf<String, String>()
            val diagnostics = linkedMapOf<String, String>()
            val deadline = android.os.SystemClock.elapsedRealtime() + 150_000
            val observations = mutableListOf<JsonObject>()
            var popular = emptyList<SManga>()
            suspend fun step(label: String, action: suspend () -> Unit) {
                operation = label
                val remaining = deadline - android.os.SystemClock.elapsedRealtime()
                if (remaining <= 0) { steps[label] = "TRANSIENT_BUDGET"; return }
                try { withTimeout(minOf(90_000, remaining)) { action() }; steps[label] = "PASS" }
                catch (e: CancellationException) { if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e; steps[label] = "TRANSIENT_TIMEOUT" }
                catch (e: Exception) {
                    // Never include raw exception messages/URLs/headers in public diagnostics.
                    steps[label] = when (e) {
                        is ProbeBudget -> "TRANSIENT_BUDGET"
                        is java.net.SocketTimeoutException, is java.net.UnknownHostException, is java.net.ConnectException -> "TRANSIENT_NETWORK"
                        else -> "SEMANTIC_ERROR"
                    }
                    val message = e.message.orEmpty().lowercase()
                    diagnostics[label] = listOf("missing slug", "missing id", "missing result", "duplicate", "incomplete", "invalid", "empty").firstOrNull { it in message }?.uppercase()?.replace(" ", "_") ?: "UNVERIFIED_IDENTITY_OR_SHAPE"
                }
            }
            fun mangaJson(m: SManga) = buildJsonObject {
                put("url", m.url); put("title", m.title); put("cover", m.thumbnail_url.orEmpty()); put("memo", m.memo)
                put("description", m.description.orEmpty()); put("author", m.author.orEmpty()); put("artist", m.artist.orEmpty()); put("genre", m.genre.orEmpty()); put("status", m.status)
            }
            step("popular") {
                val result = source.getPopularManga(1); check(result.mangas.isNotEmpty()); popular = result.mangas
                observations += buildJsonObject { put("operation", operation); put("page", 1); put("next", result.hasNextPage); put("mangas", JsonArray(result.mangas.map(::mangaJson))) }
            }
            step("pagination") {
                check(popular.isNotEmpty())
                val result = source.getPopularManga(2)
                check(result.mangas.isNotEmpty() && result.mangas.none { m -> popular.any { it.url == m.url } })
                observations += buildJsonObject { put("operation", operation); put("page", 2); put("next", result.hasNextPage); put("mangas", JsonArray(result.mangas.map(::mangaJson))) }
            }
            if (source.supportsLatest) step("latest") {
                val result = source.getLatestUpdates(1); check(result.mangas.isNotEmpty())
                observations += buildJsonObject { put("operation", operation); put("page", 1); put("next", result.hasNextPage); put("mangas", JsonArray(result.mangas.map(::mangaJson))) }
            }
            val sample = popular.firstOrNull()
            step("search") {
                check(sample != null)
                val result = source.getSearchManga(1, sample.title, FilterList())
                observations += buildJsonObject { put("operation", operation); put("query", sample.title); put("mangas", JsonArray(result.mangas.map(::mangaJson))) }
                check(result.mangas.any { it.url == sample.url })
            }
            // Popular/recent/older result positions, not manually substituted manga objects.
            for ((i, produced) in listOfNotNull(popular.firstOrNull(), popular.lastOrNull(), popular.getOrNull(popular.size / 2)).distinctBy { it.url }.withIndex()) {
                var update: SMangaUpdate? = null
                step("details-$i") {
                    update = source.getMangaUpdate(produced, emptyList(), true, true)
                    check(update!!.manga.title.isNotBlank() && update!!.manga.url == produced.url)
                    observations += buildJsonObject {
                        put("operation", "details"); put("input", mangaJson(produced)); put("manga", mangaJson(update!!.manga))
                        put("completeness", update!!.chapterCompleteness.name)
                        put("chapters", JsonArray(update!!.chapters.map { c -> buildJsonObject {
                            put("url", c.url); put("name", c.name); put("memo", c.memo); put("number", c.chapter_number); put("date", c.date_upload); put("scanlator", c.scanlator.orEmpty())
                        } }))
                    }
                }
                val detailsValid = steps["details-$i"] == "PASS"
                val chaptersValid = update != null && (update!!.chapters.isNotEmpty() || update!!.chapterCompleteness == ChapterFetchCompleteness.COMPLETE)
                steps["chapters-$i"] = when {
                    !detailsValid -> "DEPENDENT_FAILURE"
                    !chaptersValid -> "SEMANTIC_ERROR"
                    else -> "PASS_${update!!.chapterCompleteness.name}_${update!!.chapters.size}"
                }
                if (i == 0) {
                    if (steps["details-0"] != "PASS" || steps["chapters-0"]?.startsWith("PASS") != true) {
                        steps["reader"] = "DEPENDENT_FAILURE"
                    } else {
                        step("reader") {
                            val chapter = requireNotNull(update).chapters.last()
                            val pages = source.getPageList(chapter); check(pages.isNotEmpty())
                            observations += buildJsonObject { put("operation", "pages"); put("chapterUrl", chapter.url); put("chapterMemo", chapter.memo); put("images", JsonArray(pages.map { JsonPrimitive(it.imageUrl.orEmpty()) })) }
                            operation = "image"
                            val page = pages.first()
                            if (page.imageUrl.isNullOrBlank()) page.imageUrl = source.getImageUrl(page)
                            client.newCall(source.repairImageRequest(page).newBuilder().header("Range", "bytes=0-511").build()).awaitSuccess().use { response ->
                                check(response.header("Content-Type").orEmpty().startsWith("image/"))
                                val bytes = response.body.source(); bytes.request(16)
                                val magic = bytes.readByteArray(minOf(16L, bytes.buffer.size)).toString(Charsets.ISO_8859_1)
                                check(magic.startsWith("\u00ff\u00d8\u00ff") || magic.startsWith("\u0089PNG") || magic.startsWith("GIF8") || magic.startsWith("RIFF") && magic.contains("WEBP") || magic.contains("ftypavif"))
                            }
                        }
                    }
                }
            }
            val regressionQuery = when (name) {
                "TeamX" -> "God of Martial Arts"
                "Azora" -> "Rebirth Of The Urban Immortal Cultivator"
                "MangaSwat" -> "Nano Machine"
                else -> null
            }
            if (regressionQuery != null) step("regression-large") {
                val found = source.getSearchManga(1, regressionQuery, FilterList()).mangas
                    .first { it.title.equals(regressionQuery, ignoreCase = true) }
                val verified = source.getMangaUpdate(found, emptyList(), true, true)
                check(verified.chapterCompleteness == ChapterFetchCompleteness.COMPLETE && verified.chapters.size > 200)
                observations += buildJsonObject {
                    put("operation", "regression-large"); put("title", verified.manga.title); put("count", verified.chapters.size)
                    put("completeness", verified.chapterCompleteness.name)
                    put("identities", JsonArray(verified.chapters.map { JsonPrimitive(it.memo["id"]?.jsonPrimitive?.content ?: it.url) }))
                }
            }
            if (name == "Azora") {
                step("regression-novel") {
                    val text = "The child prodigy actress wants to find her father!"
                    val found = source.getSearchManga(1, text, FilterList())
                    check(found.mangas.none { it.title.equals(text, ignoreCase = true) })
                }
                step("regression-zero") {
                    val found = source.getSearchManga(1, "Rabbit Holes", FilterList()).mangas.first { it.title.equals("Rabbit Holes", ignoreCase = true) }
                    val verified = source.getMangaUpdate(found, emptyList(), true, true)
                    check(verified.chapterCompleteness == ChapterFetchCompleteness.COMPLETE)
                    observations += buildJsonObject { put("operation", "regression-zero"); put("count", verified.chapters.size); put("completeness", verified.chapterCompleteness.name) }
                }
            }
            results += buildJsonObject {
                put("name", name); put("sourceId", source.id); put("baseUrl", source.baseUrl); put("filters", source.getFilterList().isNotEmpty())
                put("steps", JsonObject(steps.mapValues { JsonPrimitive(it.value) })); put("observations", JsonArray(observations)); put("traces", JsonArray(traces.toList()))
                put("requests", requests.get()); put("diagnostics", JsonObject(diagnostics.mapValues { JsonPrimitive(it.value) }))
            }
            record("Publisher native $name: ${steps.entries.joinToString { "${it.key}=${it.value}" }} requests=${requests.get()}")
        }
        var crossSourceDiagnostic: JsonObject? = null
        val starzResult = results.find { it["name"]?.jsonPrimitive?.content == "Manga Starz" }
        val lekResult = results.find { it["name"]?.jsonPrimitive?.content == "MangaLek" }
        if (starzResult != null && lekResult != null) {
            fun extractSlugs(urls: List<String?>?): List<String>? {
                if (urls.isNullOrEmpty()) return null
                return urls.mapNotNull { url ->
                    url?.removeSuffix("/")?.substringAfterLast("/")?.takeIf { it.isNotBlank() }
                }
            }

            val starzPopUrls = starzResult["observations"]?.jsonArray?.find { it.jsonObject["operation"]?.jsonPrimitive?.content == "popular" }?.jsonObject?.get("mangas")?.jsonArray?.map { it.jsonObject["url"]?.jsonPrimitive?.content }
            val lekPopUrls = lekResult["observations"]?.jsonArray?.find { it.jsonObject["operation"]?.jsonPrimitive?.content == "popular" }?.jsonObject?.get("mangas")?.jsonArray?.map { it.jsonObject["url"]?.jsonPrimitive?.content }

            val starzPopSlugs = extractSlugs(starzPopUrls)
            val lekPopSlugs = extractSlugs(lekPopUrls)

            val identicalPopularSlugs = if (starzPopSlugs.isNullOrEmpty() || lekPopSlugs.isNullOrEmpty()) "INSUFFICIENT_EVIDENCE" else (starzPopSlugs == lekPopSlugs).toString()

            val starzPopTitles = starzResult["observations"]?.jsonArray?.find { it.jsonObject["operation"]?.jsonPrimitive?.content == "popular" }?.jsonObject?.get("mangas")?.jsonArray?.map { it.jsonObject["title"]?.jsonPrimitive?.content }
            val lekPopTitles = lekResult["observations"]?.jsonArray?.find { it.jsonObject["operation"]?.jsonPrimitive?.content == "popular" }?.jsonObject?.get("mangas")?.jsonArray?.map { it.jsonObject["title"]?.jsonPrimitive?.content }

            val identicalTitles = if (starzPopTitles.isNullOrEmpty() || lekPopTitles.isNullOrEmpty()) "INSUFFICIENT_EVIDENCE" else (starzPopTitles == lekPopTitles).toString()

            val starzChapters = starzResult["observations"]?.jsonArray?.filter { it.jsonObject["operation"]?.jsonPrimitive?.content == "details" }?.flatMap { it.jsonObject["chapters"]?.jsonArray?.map { c -> c.jsonObject["url"]?.jsonPrimitive?.content } ?: emptyList() }
            val lekChapters = lekResult["observations"]?.jsonArray?.filter { it.jsonObject["operation"]?.jsonPrimitive?.content == "details" }?.flatMap { it.jsonObject["chapters"]?.jsonArray?.map { c -> c.jsonObject["url"]?.jsonPrimitive?.content } ?: emptyList() }

            val starzChapterNames = starzResult["observations"]?.jsonArray?.filter { it.jsonObject["operation"]?.jsonPrimitive?.content == "details" }?.flatMap { it.jsonObject["chapters"]?.jsonArray?.map { c -> c.jsonObject["name"]?.jsonPrimitive?.content } ?: emptyList() }
            val lekChapterNames = lekResult["observations"]?.jsonArray?.filter { it.jsonObject["operation"]?.jsonPrimitive?.content == "details" }?.flatMap { it.jsonObject["chapters"]?.jsonArray?.map { c -> c.jsonObject["name"]?.jsonPrimitive?.content } ?: emptyList() }

            val starzChapterSteps = starzResult["steps"]?.jsonObject?.filterKeys { it.startsWith("chapters-") }?.values?.map { it.jsonPrimitive.content } ?: emptyList()
            val lekChapterSteps = lekResult["steps"]?.jsonObject?.filterKeys { it.startsWith("chapters-") }?.values?.map { it.jsonPrimitive.content } ?: emptyList()

            val starzChaptersSuccess = starzChapterSteps.isNotEmpty() && starzChapterSteps.all { it.startsWith("PASS_") }
            val lekChaptersSuccess = lekChapterSteps.isNotEmpty() && lekChapterSteps.all { it.startsWith("PASS_") }

            val identicalChapterUrls = if (!starzChaptersSuccess || !lekChaptersSuccess || starzChapters.isNullOrEmpty() || lekChapters.isNullOrEmpty()) {
                "INSUFFICIENT_EVIDENCE"
            } else {
                (starzChapters == lekChapters).toString()
            }

            val identicalChapterNames = if (!starzChaptersSuccess || !lekChaptersSuccess || starzChapterNames.isNullOrEmpty() || lekChapterNames.isNullOrEmpty()) {
                "INSUFFICIENT_EVIDENCE"
            } else {
                (starzChapterNames == lekChapterNames).toString()
            }

            val starzBase = starzResult["baseUrl"]?.jsonPrimitive?.content
            val lekBase = lekResult["baseUrl"]?.jsonPrimitive?.content
            val sameBaseUrl = if (starzBase.isNullOrBlank() || lekBase.isNullOrBlank()) "INSUFFICIENT_EVIDENCE" else (starzBase == lekBase).toString()

            crossSourceDiagnostic = buildJsonObject {
                put("type", "CROSS_SOURCE_EVIDENCE")
                put("identicalPopularSlugs", identicalPopularSlugs)
                put("identicalTitles", identicalTitles)
                put("identicalChapterUrls", identicalChapterUrls)
                put("identicalChapterNames", identicalChapterNames)
                put("sameBaseUrl", sameBaseUrl)
                put("starzChapterCount", starzChapters?.size ?: 0)
                put("lekChapterCount", lekChapters?.size ?: 0)
            }
            record("Cross-source diagnostic generated: $crossSourceDiagnostic")
        }
        val target = File(context.filesDir, "publisher-native-evidence.json")
        target.writeText(buildJsonObject {
            put("schema", 1)
            put("sources", JsonArray(results))
            if (crossSourceDiagnostic != null) {
                put("crossSourceEvidence", crossSourceDiagnostic!!)
            }
        }.toString())
        record("Publisher private evidence written; sources=${results.size}")
    }
}
