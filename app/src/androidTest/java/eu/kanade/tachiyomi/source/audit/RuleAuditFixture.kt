package eu.kanade.tachiyomi.source.audit

import android.content.Context
import eu.kanade.tachiyomi.source.internal.azora.Azora
import eu.kanade.tachiyomi.source.repair.*
import kotlinx.serialization.encodeToString
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** Emulator-only deterministic representations, delivered through the existing NetworkHelper client. */
class RuleAuditFixture(context: Context, networkClient: OkHttpClient) {
    val directory = File(context.filesDir, "rule-audit-${System.nanoTime()}")
    var nativeFailures = 0
    var feedCalls = 0
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val verifier = RuleVerifier(Base64.getEncoder().encodeToString(pair.public.encoded))
    private val id = Azora().id
    private val fields = mapOf("id" to FieldRule("id"), "url" to FieldRule("url"), "title" to FieldRule("postTitle"), "cover" to FieldRule("featuredImage"))
    private val paging = PaginationRule("p", 1, "total")
    private val catalogue = OperationRule("/catalogue", rows = "items", fields = fields, pagination = paging)
    val rules = SourceRules(sourceId = id, revision = 1, baseUrl = "https://repair-audit.example", operations = mapOf(
        "popular" to catalogue, "latest" to catalogue,
        "search" to catalogue.copy(endpoint = "/search-new", parameters = mapOf("q" to "{query}")),
        "details" to OperationRule("/details", parameters = mapOf("id" to "{id}"), rows = "post", fields = fields - "url"),
        "chapters" to OperationRule("/chapters", rows = "post.chapters", fields = mapOf("id" to FieldRule("id"), "url" to FieldRule("url"), "name" to FieldRule("name")), pagination = paging),
        "pages" to OperationRule("/pages", rows = "images", fields = mapOf("image" to FieldRule(""))),
    ))
    private val envelope: SignedRules = RulesFormat.json.encodeToString(rules).let { payload ->
        val signature = Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private); update(payload.toByteArray()) }.sign()
        SignedRules(payload, Base64.getEncoder().encodeToString(signature))
    }
    private val png = ByteArrayOutputStream().also { output ->
        android.graphics.Bitmap.createBitmap(600, 900, android.graphics.Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb(50, 120, 160)); compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output); recycle()
        }
    }.toByteArray()
    val client = networkClient.newBuilder().addInterceptor { chain ->
        val request = chain.request(); val path = request.url.encodedPath
        val page = request.url.queryParameter("p") ?: "1"
        val item = """{"id":"rule-audit-m$page","url":"/__rule_audit_m$page","postTitle":"Rule Repair Audit $page","featuredImage":"https://repair-audit.example/image.png"}"""
        val body = when (path) {
            "/$id.json" -> { feedCalls++; RulesFormat.json.encodeToString(envelope) }
            "/catalogue", "/search-new" -> """{"items":[$item],"total":2}"""
            "/details" -> """{"post":{"id":"${request.url.queryParameter("id")}","postTitle":"Rule Repair Audit 1","featuredImage":"https://repair-audit.example/image.png"}}"""
            "/chapters" -> """{"post":{"chapters":[{"id":"rule-audit-c1","url":"/__rule_audit_chapter_1","name":"Rule Repair Chapter"}]},"total":1}"""
            "/pages" -> """{"images":["https://repair-audit.example/image.png"]}"""
            "/image.png" -> ""
            else -> { nativeFailures++; "{}" }
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (path.startsWith("/api/")) 404 else 200).message("emulator rule simulation")
            .header("Content-Type", if (path.endsWith(".png")) "image/png" else "application/json")
            .body(if (path.endsWith(".png")) png.toResponseBody() else body.toResponseBody()).build()
    }.build()
    val engine = RuleRepairEngine(RuleStore(directory, verifier), HttpsRuleTransport("https://rule-audit.example", client))
    val source = RepairableSource(Azora(client), engine)
}
