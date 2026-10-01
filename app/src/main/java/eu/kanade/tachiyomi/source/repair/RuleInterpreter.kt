package eu.kanade.tachiyomi.source.repair

import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.*
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URLEncoder

/** Executes only the bounded, compiled-in JSON/HTML extraction language. */
class RuleInterpreter(private val client: OkHttpClient, val rules: SourceRules) {
    private data class Document(val json: JsonElement?, val html: Element?)
    data class Rows(val values: List<Map<String, String>>, val next: Boolean, val total: Int?)

    private fun path(root: JsonElement?, path: String): JsonElement? {
        if (path.isEmpty() || path == "$") return root
        return path.removePrefix("$.").split('.').fold(root) { node, part ->
            when (node) {
                is JsonObject -> node[part]
                is JsonArray -> part.toIntOrNull()?.let { node.getOrNull(it) }
                else -> null
            }
        }
    }
    private fun extract(doc: Document, field: FieldRule): String? {
        return if (doc.json != null) (path(doc.json, field.path) as? JsonPrimitive)?.contentOrNull
        else doc.html?.let { root ->
            val node = if (field.path.isBlank()) root else root.selectFirst(field.path)
            node?.let { if (field.attribute.isBlank()) it.text() else it.attr(field.attribute) }?.takeIf { it.isNotBlank() }
        }
    }
    private fun variables(url: String, memo: JsonObject, page: Int, query: String): Map<String, String> {
        val parsed = rules.baseUrl.toHttpUrl().resolve(url.substringBefore('#'))
        val path = parsed?.encodedPath?.trimEnd('/')?.ifBlank { "/" } ?: "/"
        val slug = parsed?.pathSegments?.lastOrNull { it.isNotBlank() }.orEmpty()
        return mapOf("url" to url, "path" to path, "slug" to slug, "mangaSlug" to slug,
            "id" to (memo.entries.filter { it.key == "id" || it.key == "chapterId" || it.key == "remoteId" || it.key.endsWith(".id") }
                .mapNotNull { (it.value as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }.distinct().singleOrNull()
                ?: url.substringAfter('#', "").ifBlank { url.trim('/').takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }.orEmpty() }),
            "page" to page.toString(), "query" to query) + (0..5).associate { "segment$it" to parsed?.pathSegments?.getOrNull(it).orEmpty() }
    }
    private fun expand(value: String, variables: Map<String, String>, encode: Boolean = false): String {
        return Regex("\\{([A-Za-z][A-Za-z0-9]*)\\}").replace(value) { match ->
            val v = variables[match.groupValues[1]] ?: throw IOException("Unknown rule placeholder")
            if (encode && match.groupValues[1] != "path") URLEncoder.encode(v, "UTF-8").replace("+", "%20") else v
        }
    }
    private fun absolute(value: String): String {
        val u = rules.baseUrl.toHttpUrl().resolve(value) ?: throw IOException("Invalid remote URL")
        RulesFormat.publicHttps(u.toString())
        return u.toString()
    }
    suspend fun rows(operation: String, url: String = "", memo: JsonObject = JsonObject(emptyMap()), page: Int = 1, query: String = ""): Rows = withTimeoutOrNull(15_000) {
        val o = rules.operations[operation] ?: throw IOException("Rule operation unsupported")
        val vars = variables(url, memo, page, query)
        val endpoint = expand(o.endpoint, vars, encode = true)
        val builder = rules.baseUrl.toHttpUrl().resolve(endpoint)?.newBuilder() ?: throw IOException("Invalid endpoint")
        val params = o.parameters.mapValues { expand(it.value, vars) }.toMutableMap()
        o.pagination?.let { params[it.pageParameter] = page.toString() }
        val request = Request.Builder()
        rules.headers.forEach { (k, v) -> request.header(k, expand(v, vars)) }
        if (o.method == "GET") params.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        request.url(builder.build())
        if (o.method == "POST") request.post(if (o.bodyEncoding == "JSON") {
            JsonObject(params.mapValues { JsonPrimitive(it.value) }).toString().toRequestBody("application/json".toMediaType())
        } else FormBody.Builder().apply { params.forEach { (k, v) -> add(k, v) } }.build())
        val body = client.newCall(request.build()).awaitSuccess().use { response ->
            RulesFormat.publicHttps(response.request.url.toString())
            val source = response.body.source()
            // Hard upper bound, including unknown Content-Length and decompressed bodies.
            source.request(2 * 1024 * 1024L + 1)
            if (source.buffer.size > 2 * 1024 * 1024 || response.body.contentLength() > 2 * 1024 * 1024) throw IOException("Rule response exceeds limit")
            source.readUtf8()
        }
        if (body.isBlank()) throw IOException("Empty source representation")
        val doc = if (o.format == "JSON") Document(RulesFormat.json.parseToJsonElement(body), null)
        else Document(null, Jsoup.parse(body))
        if (doc.html != null && doc.html.select("#challenge-form,.cf-challenge,[data-sitekey]").isNotEmpty()) throw IOException("Source challenge representation")
        val raw: List<Document> = if (doc.json != null) {
            when (val node = path(doc.json, o.rows)) {
                is JsonArray -> node.map { Document(it, null) }
                is JsonObject, is JsonPrimitive -> listOf(Document(node, null))
                else -> throw IOException("Missing source rows")
            }
        } else {
            if (o.rows.isBlank()) listOf(doc) else doc.html!!.select(o.rows).map { Document(null, it) }
        }
        if (raw.size > 2000) throw IOException("Unbounded rule rows")
        val values = raw.mapNotNull { row ->
            if (o.required.any { (field, allowed) -> extract(row, FieldRule(field)) !in allowed }) return@mapNotNull null
            o.fields.mapValues { (_, spec) ->
                val value = extract(if (spec.root) doc else row, spec)?.takeIf { it.isNotBlank() }
                    ?: if (spec.optional) "" else throw IOException("Missing required source field ${spec.path}")
                if (spec.transform == "BASE64" && value.isNotBlank()) {
                    try { String(java.util.Base64.getDecoder().decode(value), Charsets.UTF_8) }
                    catch (e: IllegalArgumentException) { throw IOException("Invalid encoded source field", e) }
                } else value
            }.toMutableMap().also { fields ->
                if ("url" in fields) fields["url"] = expand(o.identity, vars + fields)
                listOf("image", "cover").forEach { key -> if (!fields[key].isNullOrBlank()) fields[key] = absolute(fields.getValue(key)) }
            }
        }
        val p = o.pagination
        val total = p?.total?.takeIf { it.isNotBlank() }?.let { extract(doc, FieldRule(it))?.toIntOrNull() ?: throw IOException("Missing declared total") }
        if (total != null && total < 0) throw IOException("Invalid declared total")
        val next = when {
            p == null -> false
            p.hasNext.isNotBlank() -> when (extract(doc, FieldRule(p.hasNext))) { "true", "1" -> true; "false", "0" -> false; else -> throw IOException("Missing next-page state") }
            p.nextSelector.isNotBlank() -> doc.html?.select(p.nextSelector)?.isNotEmpty() ?: throw IOException("Invalid HTML pagination")
            else -> page.toLong() * p.pageSize < requireNotNull(total)
        }
        if (next && raw.isEmpty()) throw IOException("Empty intermediate source page")
        if (p != null && total != null && p.hasNext.isNotBlank() && next != (page.toLong() * p.pageSize < total)) throw IOException("Inconsistent pagination metadata")
        // Server paging uses raw rows / declared metadata, never a filtered item count.
        Rows(values, next, total)
    } ?: throw java.net.SocketTimeoutException("Source rule request timed out")
    private fun manga(v: Map<String, String>): SManga = SManga.create().apply {
        url = v["url"] ?: throw IOException("Missing manga identity")
        title = v.getValue("title")
        if (url.isBlank() || title.isBlank()) throw IOException("Invalid manga identity")
        thumbnail_url = v["cover"]?.takeIf { it.isNotBlank() }
        memo = buildJsonObject { put("id", v.getValue("id")); v.filterKeys { it.startsWith("memo.") }.forEach { (k, value) -> put(k.removePrefix("memo."), value) } }
        description = v["description"]?.takeIf { it.isNotBlank() }
        author = v["author"]?.takeIf { it.isNotBlank() }
        artist = v["artist"]?.takeIf { it.isNotBlank() }
        genre = v["genre"]?.takeIf { it.isNotBlank() }
    }
    suspend fun catalogue(op: String, page: Int, query: String = ""): MangasPage {
        val rows = rows(op, page = page, query = query)
        if (rows.values.map { it["id"] }.toSet().size != rows.values.size || rows.values.map { it["url"] }.toSet().size != rows.values.size) throw IOException("Duplicate catalogue identities")
        if (op != "search" && page == 1 && rows.values.isEmpty()) throw IOException("Empty populated catalogue")
        return MangasPage(rows.values.map(::manga), rows.next)
    }
    suspend fun details(manga: SManga): SManga {
        val v = rows("details", manga.url, manga.memo).values.singleOrNull() ?: throw IOException("Unexpected manga details")
        val expected = variables(manga.url, manga.memo, 1, "").getValue("id")
        if (expected.isNotBlank()) {
            if (v["id"] != expected) throw IOException("Details identity mismatch")
        } else {
            // Older HTML manga have only a stored route. Require a returned canonical route,
            // never title similarity, to verify the payload before learning its remote ID.
            val canonical = v["url"] ?: throw IOException("Legacy details require canonical identity")
            val returned = rules.baseUrl.toHttpUrl().resolve(canonical) ?: throw IOException("Invalid canonical identity")
            val requested = rules.baseUrl.toHttpUrl().resolve(manga.url) ?: throw IOException("Invalid requested identity")
            if (returned.encodedPath.trimEnd('/') != requested.encodedPath.trimEnd('/') || returned.encodedQuery != requested.encodedQuery) throw IOException("Canonical details identity mismatch")
        }
        return manga.copy().apply {
            memo = JsonObject(memo + ("id" to JsonPrimitive(v.getValue("id"))))
            title = v.getValue("title").takeIf { it.isNotBlank() } ?: throw IOException("Missing details title")
            v["cover"]?.takeIf { it.isNotBlank() }?.let { thumbnail_url = it }
            v["description"]?.takeIf { it.isNotBlank() }?.let { description = it }
            v["author"]?.takeIf { it.isNotBlank() }?.let { author = it }
            v["artist"]?.takeIf { it.isNotBlank() }?.let { artist = it }
            v["genre"]?.takeIf { it.isNotBlank() }?.let { genre = it }
            v["chapterTotal"]?.let { count ->
                val total = count.toIntOrNull()?.takeIf { it >= 0 } ?: throw IOException("Invalid details chapter total")
                memo = JsonObject(memo + ("rules.chapterTotal" to JsonPrimitive(total)))
            }
            initialized = true
        }
    }
    suspend fun chapters(manga: SManga, maxPages: Int = 25): List<SChapter> {
        val detailTotal = (manga.memo["rules.chapterTotal"] as? JsonPrimitive)?.intOrNull
        val all = mutableListOf<SChapter>()
        val seenPages = mutableSetOf<List<String>>()
        var total: Int? = null
        val p = requireNotNull(rules.operations.getValue("chapters").pagination)
        for (page in 1..minOf(maxPages, p.maxPages)) {
            val rows = rows("chapters", manga.url, manga.memo, page)
            if (page > 1 && rows.values.isEmpty()) throw IOException("Missing chapter page")
            val ids = rows.values.map { it.getValue("id") }
            if (!seenPages.add(ids)) throw IOException("Repeated chapter page")
            if (detailTotal != null && rows.total != null && detailTotal != rows.total) throw IOException("Details/chapters total mismatch")
            if (page == 1) total = rows.total ?: detailTotal else if ((rows.total ?: detailTotal) != total) throw IOException("Chapter total changed during fetch")
            all += rows.values.map { v -> SChapter.create().apply {
                url = v.getValue("url"); name = v.getValue("name")
                if (url.isBlank()) throw IOException("Missing chapter URL")
                memo = buildJsonObject { put("id", v.getValue("id")); v.filterKeys { it.startsWith("memo.") }.forEach { (k, value) -> put(k.removePrefix("memo."), value) } }
                chapter_number = v["number"]?.toFloatOrNull() ?: -1f
                scanlator = v["scanlator"]?.takeIf { it.isNotBlank() }
                date_upload = v["date"]?.toLongOrNull() ?: 0L
            } }
            if (all.map { it.memo["id"] }.toSet().size != all.size || all.map { it.url }.toSet().size != all.size) throw IOException("Duplicate chapter identity")
            if (!rows.next) {
                if (total == null) throw IOException("Chapter completeness has no verified total")
                if (all.size != total) throw IOException("Incomplete chapter total")
                if (all.isEmpty() && total != 0) throw IOException("Unconfirmed empty chapters")
                return all
            }
        }
        throw IOException("Chapter verification exceeded bounded page budget")
    }
    suspend fun pages(chapter: SChapter): List<Page> {
        val values = rows("pages", chapter.url, chapter.memo).values
        if (values.isEmpty() || values.map { it["image"] }.toSet().size != values.size) throw IOException("Empty or duplicate reader pages")
        val ordered = if (values.any { "order" in it }) {
            val orders = values.map { it["order"]?.toIntOrNull() ?: throw IOException("Invalid reader page order") }
            if (orders.toSet().size != orders.size) throw IOException("Duplicate reader page order")
            values.sortedBy { it.getValue("order").toInt() }
        } else values
        return ordered.mapIndexed { index, v -> Page(index, imageUrl = v.getValue("image")) }
    }
}
