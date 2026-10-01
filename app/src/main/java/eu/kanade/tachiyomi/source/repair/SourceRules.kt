package eu.kanade.tachiyomi.source.repair

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

/** A deliberately finite language: no expressions, scripts, loops or downloaded classes. */
@Serializable
data class SourceRules(
    val schema: Int = 1,
    val sourceId: Long,
    val revision: Long,
    val baseUrl: String,
    val operations: Map<String, OperationRule>,
    val headers: Map<String, String> = emptyMap(),
    val validationQuery: String = "",
    val validationMangaId: String = "",
)

@Serializable
data class OperationRule(
    val endpoint: String,
    val method: String = "GET",
    val bodyEncoding: String = "FORM",
    val parameters: Map<String, String> = emptyMap(),
    val format: String = "JSON",
    val rows: String = "",
    val fields: Map<String, FieldRule>,
    val identity: String = "{url}",
    val pagination: PaginationRule? = null,
    val required: Map<String, List<String>> = emptyMap(),
)

@Serializable
data class FieldRule(val path: String = "", val attribute: String = "", val optional: Boolean = false, val root: Boolean = false, val transform: String = "NONE")

@Serializable
data class PaginationRule(
    val pageParameter: String = "page",
    val pageSize: Int = 24,
    val total: String = "",
    val hasNext: String = "",
    val nextSelector: String = "",
    val maxPages: Int = 25,
)

@Serializable
data class SignedRules(val payload: String, val signature: String)

object RulesFormat {
    val json = Json { ignoreUnknownKeys = false }
    val operations = setOf("popular", "latest", "search", "details", "chapters", "pages")
    fun validate(r: SourceRules) {
        require(r.schema == 1 && r.revision > 0 && r.sourceId > 0) { "Unsupported schema or revision" }
        publicHttps(r.baseUrl)
        require(r.validationQuery.length <= 256 && r.validationMangaId.length <= 128 && r.validationQuery.isBlank() == r.validationMangaId.isBlank()) { "Validation search requires both query and identity" }
        require(r.operations.keys.containsAll(setOf("popular", "search", "details", "chapters", "pages"))) { "Incomplete rule set" }
        require(r.operations.size <= 6 && r.operations.keys.all { it in operations })
        require(r.headers.size <= 12 && r.headers.keys.all { it.lowercase() in setOf("referer", "origin", "user-agent", "accept", "accept-language") })
        require(r.headers.values.all { it.length <= 1024 && '\n' !in it && '\r' !in it })
        r.operations.forEach { (name, o) ->
            require((o.endpoint.startsWith('/') || o.endpoint == "{path}" || o.endpoint.startsWith("{path}/")) && !o.endpoint.startsWith("//") && o.endpoint.length <= 1024)
            require(o.bodyEncoding in setOf("FORM", "JSON"))
            require(o.method in setOf("GET", "POST") && o.format in setOf("JSON", "HTML"))
            if (o.format == "HTML") require((listOf(o.rows, o.pagination?.nextSelector.orEmpty()) + o.fields.values.map { it.path }).none { ":matches" in it.lowercase() || ":has(" in it.lowercase() }) { "Unbounded selector operators are unsupported" }
            require(o.rows.length <= 256 && o.identity.length <= 1024 && o.parameters.values.all { it.length <= 1024 })
            require(o.fields.size <= 24 && o.parameters.size <= 20 && o.required.size <= 8)
            require(o.fields.values.all { it.path.length <= 256 && it.attribute.length <= 64 && it.transform in setOf("NONE", "BASE64") })
            require(o.fields.keys.containsAll(when (name) {
                "details" -> setOf("id", "title")
                "chapters" -> setOf("id", "url", "name")
                "pages" -> setOf("image")
                else -> setOf("id", "url", "title")
            })) { "Required identity fields absent" }
            val identities = when (name) {
                "pages" -> setOf("image")
                "details" -> setOf("id", "title")
                "chapters" -> setOf("id", "url", "name")
                else -> setOf("id", "url", "title")
            }
            require(identities.all { o.fields[it]?.optional == false }) { "Required fields cannot be optional" }
            if (name in setOf("popular", "latest", "search", "chapters")) require(o.pagination != null) { "Pagination must be explicit" }
            o.pagination?.let { p ->
                require(p.pageSize in 1..200 && p.maxPages in 1..25 && p.pageParameter.matches(Regex("[A-Za-z0-9_-]{1,40}")))
                require(p.total.isNotBlank() || p.hasNext.isNotBlank() || p.nextSelector.isNotBlank()) { "Unverified pagination" }
            }
        }
    }
    fun publicHttps(value: String) {
        val u = value.toHttpUrlOrNull() ?: throw IOException("Invalid rule URL")
        require(u.isHttps && u.username.isEmpty() && u.password.isEmpty()) { "HTTPS required" }
        val h = u.host.lowercase()
        require(h.contains('.') && !h.endsWith(".local") && h != "localhost" && !h.matches(Regex("[0-9.]+")) && ':' !in h) { "Public host required" }
    }
}
