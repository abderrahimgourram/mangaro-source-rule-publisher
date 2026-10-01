package eu.kanade.tachiyomi.source.repair

import eu.kanade.tachiyomi.source.model.*
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class RuleRepairEngineTest {
    @TempDir lateinit var dir: File
    private val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private fun signed(rules: SourceRules): SignedRules {
        val payload = RulesFormat.json.encodeToString(rules)
        val signer = Signature.getInstance("SHA256withECDSA").apply { initSign(key.private); update(payload.toByteArray()) }
        return SignedRules(payload, Base64.getEncoder().encodeToString(signer.sign()))
    }
    private fun store() = RuleStore(dir, RuleVerifier(Base64.getEncoder().encodeToString(key.public.encoded)))
    private fun field(path: String, optional: Boolean = false) = FieldRule(path, optional = optional)
    private fun rules(revision: Long = 2, html: Boolean = false, sourceId: Long = 44): SourceRules {
        val old = revision == 1L
        val paging = PaginationRule(if (old) "page" else "p", pageSize = 1, total = "total")
        val fields = mapOf("id" to field("id"), "url" to field("url"), "title" to field(if (old) "title" else "postTitle"), "cover" to field(if (old) "cover" else "featuredImage"))
        val catalogue = OperationRule("/catalogue", rows = "items", fields = fields, pagination = paging)
        val popular = if (html) catalogue.copy(format = "HTML", rows = if (old) ".old-card" else ".comic-card", fields = mapOf(
            "id" to FieldRule(attribute = "data-id"), "url" to FieldRule("a", "href"), "title" to FieldRule(".name"), "cover" to FieldRule("img", "src")), pagination = paging.copy(total = "", nextSelector = if (old) ".old-next" else ".next")) else catalogue
        return SourceRules(sourceId = sourceId, revision = revision, baseUrl = if (old) "https://old.example" else "https://new.example", operations = mapOf(
            "popular" to popular,
            "latest" to catalogue.copy(endpoint = "/latest"),
            "search" to catalogue.copy(endpoint = if (old) "/search-old" else "/search-new", parameters = mapOf("q" to "{query}")),
            "details" to OperationRule("/details", parameters = mapOf("id" to "{id}"), rows = "post", fields = fields - "url"),
            "chapters" to OperationRule("/chapters", parameters = mapOf("id" to "{id}"), rows = if (old) "chapters" else "post.chapters", fields = mapOf("id" to field("id"), "url" to field("url"), "name" to field("name"), "number" to field("number")), pagination = paging),
            "pages" to OperationRule("/pages", parameters = mapOf("id" to "{id}"), rows = if (old) "pages" else "images", fields = mapOf("image" to field(""))),
        ))
    }
    private fun client(html: Boolean = false, badChapters: Boolean = false, badImage: Boolean = false, requests: MutableList<Request> = mutableListOf(), mutate: (Request, String) -> String = { _, body -> body }): OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request(); requests += request
        val url = request.url
        val page = url.queryParameter("p")?.toIntOrNull() ?: 1
        val item = """{"id":"m$page","url":"/m$page","postTitle":"Manga $page","featuredImage":"https://new.example/cover.jpg"}"""
        val body = when (url.encodedPath) {
            "/44.json", "/55.json" -> RulesFormat.json.encodeToString(signed(rules(sourceId = if (url.encodedPath == "/55.json") 55 else 44, html = html)))
            "/catalogue" -> if (html) """<div class="comic-card" data-id="m$page"><a href="/m$page"><span class="name">Manga $page</span><img src="/cover.jpg"></a></div>${if (page == 1) "<a class='next'>next</a>" else ""}""" else """{"items":[$item],"total":2}"""
            "/latest" -> """{"items":[$item],"total":2}"""
            "/search-new" -> """{"items":[{"id":"m1","url":"/m1","postTitle":"Manga 1","featuredImage":"https://new.example/cover.jpg"}],"total":1}"""
            "/details" -> """{"post":{"id":"${url.queryParameter("id")}","postTitle":"Manga 1","featuredImage":"https://new.example/cover.jpg"}}"""
            "/chapters" -> if (badChapters) """{"post":{"chapters":[]},"total":4}""" else """{"post":{"chapters":[{"id":"c1","url":"/chapter-1","name":"Chapter one","number":1}]},"total":1}"""
            "/pages" -> """{"images":["https://new.example/image.jpg"]}"""
            "/image.jpg" -> if (badImage) "<html>Challenge</html>" else "\u00ff\u00d8\u00fftest"
            else -> "{}"
        }
        val code = if (url.host == "old.example" || url.encodedPath == "/search-old") 404 else 200
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("simulation")
            .header("Content-Type", if (url.encodedPath.endsWith(".jpg")) "image/jpeg" else "application/json")
            .body(if (url.encodedPath == "/image.jpg" && !badImage) byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0, 1).toResponseBody() else mutate(request, body).toResponseBody()).build()
    }.build()
    private fun source(client: OkHttpClient, idValue: Long = 44) = object : HttpSource() {
        override val id = idValue
        override val name = "Existing Arabic Source"
        override val lang = "ar"
        override val baseUrl = "https://old.example"
        override val supportsLatest = true
        override val client = client
        override fun headersBuilder() = Headers.Builder()
        override suspend fun getPopularManga(page: Int): MangasPage = throw IOException("Missing title field")
    }
    @Test fun `signed repair changes fields endpoints selectors domain and pagination then survives restart`() = runBlocking<Unit> {
        for (html in listOf(false, true)) {
            val root = File(dir, html.toString())
            val verifier = RuleVerifier(Base64.getEncoder().encodeToString(key.public.encoded))
            val store = RuleStore(root, verifier)
            store.stage(44, signed(rules(1, html))); store.activate(44)
            val requests = mutableListOf<Request>()
            val client = client(html, requests = requests)
            val engine = RuleRepairEngine(store, HttpsRuleTransport("https://rules.example", client), mihon.domain.source.health.SourceHealthMonitor())
            val adapter = RepairableSource(source(client), engine)
            val page = adapter.getPopularManga(1)
            assertEquals("Manga 1", page.mangas.single().title)
            assertEquals("https://new.example/cover.jpg", page.mangas.single().thumbnail_url)
            assertTrue(page.hasNextPage)
            assertEquals("/m2", adapter.getPopularManga(2).mangas.single().url)
            val update = adapter.getMangaUpdate(page.mangas.single(), emptyList(), true, true)
            assertEquals(ChapterFetchCompleteness.COMPLETE, update.chapterCompleteness)
            assertEquals("c1", update.chapters.single().memo["id"]?.toString()?.trim('"'))
            assertEquals("https://new.example/image.jpg", adapter.getPageList(update.chapters.single()).single().imageUrl)
            assertEquals(2, store.active(44)?.revision)
            assertEquals(1, verifier.verify(store.read(44).lastKnownGood!!).revision)
            assertEquals(1, requests.count { it.url.encodedPath == "/44.json" })
            assertTrue(requests.any { it.url.encodedPath == "/search-new" })
            assertTrue(requests.any { it.url.host == "new.example" && it.url.queryParameter("p") == "2" })
            val restarted = RuleRepairEngine(RuleStore(root, verifier), RuleTransport { throw IOException("offline") }, mihon.domain.source.health.SourceHealthMonitor())
            assertEquals("Manga 1", RepairableSource(source(client), restarted).getPopularManga(1).mangas.single().title)
        }
    }
    @Test fun `signature malformed schema downgrade and wrong source are rejected`() {
        val store = store()
        val valid = signed(rules())
        assertThrows(Exception::class.java) { store.stage(44, valid.copy(signature = "AAAA")) }
        assertThrows(Exception::class.java) { store.stage(44, valid.copy(payload = valid.payload.replace("Manga", "tampered") + " ")) }
        assertThrows(Exception::class.java) { store.stage(44, signed(rules().copy(schema = 9))) }
        assertThrows(Exception::class.java) { store.stage(55, valid) }
        assertThrows(Exception::class.java) { store.stage(44, signed(rules().copy(baseUrl = "https://127.0.0.1"))) }
        assertThrows(Exception::class.java) { store.stage(44, signed(rules().copy(operations = emptyMap()))) }
        store.stage(44, valid); store.activate(44)
        assertThrows(Exception::class.java) { store.stage(44, signed(rules(1))) }
        store.rollback(44)
        assertNull(store.active(44))
        assertThrows(Exception::class.java) { store.stage(44, signed(rules(1))) }
    }
    @Test fun `bad candidate preserves active and rollback never lowers version floor`() = runBlocking<Unit> {
        val store = store(); store.stage(44, signed(rules(1))); store.activate(44)
        val client = client(badChapters = true)
        val engine = RuleRepairEngine(store, RuleTransport { signed(rules()) }, mihon.domain.source.health.SourceHealthMonitor())
        assertFalse(engine.check(source(client)))
        assertEquals(1, store.active(44)?.revision)
        assertNull(store.read(44).candidate)
        assertEquals(2, store.read(44).rejectedRevision)
        assertThrows(Exception::class.java) { store.stage(44, signed(rules())) }
    }
    @Test fun `one source candidate failure does not prevent other source activation`() = runBlocking<Unit> {
        val store = store()
        val engine = RuleRepairEngine(store, RuleTransport { signed(rules(sourceId = it)) }, mihon.domain.source.health.SourceHealthMonitor())
        assertFalse(engine.check(source(client(badChapters = true))))
        assertTrue(engine.check(source(client(), 55)))
        assertNull(store.active(44)); assertEquals(2, store.active(55)?.revision)
    }
    @Test fun `offline update feed leaves healthy cached rule and health untouched`() = runBlocking<Unit> {
        val store = store(); store.stage(44, signed(rules())); store.activate(44)
        val health = mihon.domain.source.health.SourceHealthMonitor()
        val engine = RuleRepairEngine(store, RuleTransport { throw IOException("offline") }, health)
        assertFalse(engine.check(source(client())))
        assertEquals(2, store.active(44)?.revision)
        assertEquals(mihon.domain.source.health.SourceHealthMonitor.State.HEALTHY, health.health(44).state)
        assertEquals("Manga 1", RepairableSource(source(client()), engine).getPopularManga(1).mangas.single().title)
    }
    @Test fun `lost existing chapter identities reject candidate even matching declared total`() = runBlocking<Unit> {
        val store = store()
        val engine = RuleRepairEngine(store, RuleTransport { signed(rules()) }, mihon.domain.source.health.SourceHealthMonitor())
        val manga = SManga.create().apply { url = "/m1"; title = "Manga 1" }
        engine.remember(44, manga, listOf(SChapter.create().apply { url = "/old-chapter"; name = "old" }))
        assertFalse(engine.check(source(client())))
        assertNull(store.active(44))
    }
    @Test fun `maintenance stale skip prevents repeated rule requests`() = runBlocking<Unit> {
        var calls = 0
        val engine = RuleRepairEngine(store(), RuleTransport { calls++; throw IOException("offline") }, clock = { 1000 })
        assertFalse(engine.check(source(client()))); assertFalse(engine.check(source(client()), force = true))
        assertEquals(1, calls)
    }
    @Test fun `HTTP 200 with forged image MIME is rejected`() = runBlocking<Unit> {
        val engine = RuleRepairEngine(store(), RuleTransport { signed(rules()) }, mihon.domain.source.health.SourceHealthMonitor())
        assertFalse(engine.check(source(client(badImage = true))))
        assertNull(engine.store.active(44))
    }
    @Test fun `repeated pagination wrong details identity null fields and inconsistent totals are rejected`() = runBlocking<Unit> {
        val corruptions: List<(Request, String) -> String> = listOf(
            { request, body -> if (request.url.encodedPath == "/catalogue") body.replace("m2", "m1") else body },
            { request, body -> if (request.url.encodedPath == "/details") body.replace("m1", "different-id") else body },
            { request, body -> if (request.url.encodedPath == "/catalogue") body.replace("\"postTitle\":\"Manga 1\"", "\"postTitle\":null") else body },
            { request, body -> if (request.url.encodedPath == "/chapters") body.replace("\"total\":1", "\"total\":0") else body },
        )
        for ((index, corruption) in corruptions.withIndex()) {
            val root = File(dir, "invalid-$index")
            val store = RuleStore(root, RuleVerifier(Base64.getEncoder().encodeToString(key.public.encoded)))
            val engine = RuleRepairEngine(store, RuleTransport { signed(rules()) }, mihon.domain.source.health.SourceHealthMonitor())
            assertFalse(engine.check(source(client(mutate = corruption))), "corruption $index")
            assertNull(store.active(44))
        }
    }
    @Test fun `failed active rule restores previous verified version and does not repeat repair indefinitely`() = runBlocking<Unit> {
        val store = store()
        store.stage(44, signed(rules(1))); store.activate(44)
        store.stage(44, signed(rules())); store.activate(44)
        var calls = 0
        val engine = RuleRepairEngine(store, RuleTransport { calls++; throw IOException("offline") }, mihon.domain.source.health.SourceHealthMonitor())
        val adapter = RepairableSource(source(client(badChapters = true)), engine)
        val manga = SManga.create().apply { url = "/m1"; title = "Manga 1"; memo = kotlinx.serialization.json.buildJsonObject { put("id", kotlinx.serialization.json.JsonPrimitive("m1")) } }
        try { adapter.getMangaUpdate(manga, emptyList(), true, true); fail<Unit>("Expected clean failure") } catch (_: IOException) { }
        assertEquals(1, store.active(44)?.revision)
        assertEquals(2, store.read(44).highWater)
        assertEquals(1, calls)
    }
    @Test fun `database identity floor survives process restart and rejects truncated candidate`() = runBlocking<Unit> {
        val existing = SChapter.create().apply { url = "/old"; name = "Chapter"; memo = kotlinx.serialization.json.buildJsonObject { put("id", kotlinx.serialization.json.JsonPrimitive("c2")) } }
        val engine = RuleRepairEngine(store(), RuleTransport { signed(rules()) }, mihon.domain.source.health.SourceHealthMonitor(), existingChapters = { _, _ -> listOf(existing) })
        assertFalse(engine.check(source(client())))
        assertNull(engine.store.active(44))
    }

    @Test fun `cached signature damage falls back safely while keeping independent downgrade floor`() {
        val store = store(); store.stage(44, signed(rules())); store.activate(44)
        File(dir, "44.json").writeText("broken snapshot")
        assertNull(store.active(44))
        assertEquals(2, store.read(44).highWater)
        assertThrows(Exception::class.java) { store.stage(44, signed(rules(1))) }
    }
    @Test fun `exact accepted payload can be revalidated after rollback without accepting older remote rules`() {
        val store = store(); val accepted = signed(rules())
        store.stage(44, signed(rules(1))); store.activate(44)
        store.stage(44, accepted); store.activate(44)
        store.rollback(44)
        assertThrows(Exception::class.java) { store.stage(44, signed(rules(1))) }
        store.stage(44, accepted); store.activate(44)
        assertEquals(2, store.active(44)?.revision)
    }

    @Test fun `repeated native pagination triggers signed repair and retries requested page once`() = runBlocking<Unit> {
        val client = client()
        val original = object : HttpSource() {
            override val id = 44L
            override val name = "Existing Source"
            override val lang = "ar"
            override val baseUrl = "https://old.example"
            override val supportsLatest = true
            override val client = client
            override fun headersBuilder() = Headers.Builder()
            override suspend fun getPopularManga(page: Int) = MangasPage(listOf(SManga.create().apply { url = "/m1"; title = "Manga 1" }), true)
        }
        var calls = 0
        val engine = RuleRepairEngine(store(), RuleTransport { calls++; signed(rules()) }, mihon.domain.source.health.SourceHealthMonitor())
        val adapter = RepairableSource(original, engine)
        assertEquals("/m1", adapter.getPopularManga(1).mangas.single().url)
        assertEquals("/m2", adapter.getPopularManga(2).mangas.single().url)
        assertEquals(1, calls)
        assertEquals(2, engine.store.active(44)?.revision)
    }

    @Test fun `signed small search sample validates without narrowing real Popular catalogue`() = runBlocking<Unit> {
        val requests = mutableListOf<Request>()
        val client = client(requests = requests, mutate = { request, body -> if (request.url.encodedPath == "/catalogue") body.replace("m1", "large-m1") else body })
        val profile = rules().copy(validationQuery = "Small validation series", validationMangaId = "m1")
        val engine = RuleRepairEngine(store(), RuleTransport { signed(profile) }, mihon.domain.source.health.SourceHealthMonitor())
        assertTrue(engine.check(source(client)))
        assertTrue(requests.any { it.url.encodedPath == "/search-new" && it.url.queryParameter("q") == "Small validation series" })
        assertTrue(requests.filter { it.url.encodedPath == "/details" }.all { it.url.queryParameter("id") == "m1" })
        assertEquals("/large-m1", RepairableSource(source(client), engine).getPopularManga(1).mangas.first().url)
    }

    @Test fun `HTML details POST chapters and Reader preserve paths and move stored old domain`() = runBlocking<Unit> {
        val requests = mutableListOf<Request>()
        val htmlClient = client(requests = requests).newBuilder().addInterceptor { chain -> chain.proceed(chain.request()) }.build()
        // Prepend the HTML representation interceptor; all requests still use the same OkHttp path.
        val client = htmlClient.newBuilder().apply { interceptors().add(0) { chain ->
            val request = chain.request()
            val html = when (request.url.encodedPath) {
                "/m1" -> "<div class='series' data-id='m1'><span class='title'>Manga 1</span></div><span class='total'>1</span>"
                "/m1/ajax" -> "<div class='chapters'><a data-id='c1' href='/chapter-1'>Chapter one</a></div><span class='total'>1</span>"
                "/chapter-1" -> "<div class='reader'><img src='/image.jpg'></div>"
                else -> null
            }
            if (html == null) chain.proceed(request) else {
                requests += request
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("HTML simulation").body(html.toResponseBody()).build()
            }
        } }.build()
        val profile = rules().let { r -> r.copy(operations = r.operations + mapOf(
            "details" to OperationRule("{path}", format = "HTML", rows = ".series", fields = mapOf(
                "id" to FieldRule(attribute = "data-id"), "title" to FieldRule(".title"), "chapterTotal" to FieldRule(".total", root = true))),
            "chapters" to OperationRule("{path}/ajax", method = "POST", bodyEncoding = "JSON", parameters = mapOf("postId" to "{id}"), format = "HTML", rows = ".chapters a",
                fields = mapOf("id" to FieldRule(attribute = "data-id"), "url" to FieldRule(attribute = "href"), "name" to FieldRule()), pagination = PaginationRule("p", 24, total = ".total")),
            "pages" to OperationRule("{path}", format = "HTML", rows = ".reader img", fields = mapOf("image" to FieldRule(attribute = "src"))),
        )) }
        val engine = RuleRepairEngine(store(), RuleTransport { signed(profile) }, mihon.domain.source.health.SourceHealthMonitor())
        assertTrue(engine.check(source(client)))
        val adapter = RepairableSource(source(client), engine)
        val produced = adapter.getPopularManga(1).mangas.single()
        val legacy = produced.copy().apply { url = "https://old.example/m1#legacy" }
        val update = adapter.getMangaUpdate(legacy, emptyList(), true, true)
        assertEquals(ChapterFetchCompleteness.COMPLETE, update.chapterCompleteness)
        assertEquals(legacy.url, update.manga.url)
        assertEquals(1, adapter.getPageList(update.chapters.single()).size)
        assertTrue(requests.any { it.url.host == "new.example" && it.url.encodedPath == "/m1" })
        assertTrue(requests.any { it.method == "POST" && it.url.encodedPath == "/m1/ajax" && it.body?.contentType().toString() == "application/json; charset=utf-8" })
        assertFalse(requests.any { "%2F" in it.url.encodedPath })
    }

    @Test fun `legacy bare manga ID and namespaced chapter memo bind stable remote requests`() = runBlocking<Unit> {
        val requests = mutableListOf<Request>()
        val client = client(requests = requests)
        val profile = rules().let { r -> r.copy(operations = r.operations + ("pages" to r.operations.getValue("pages").copy(parameters = mapOf("id" to "{id}", "type" to "{segment0}")))) }
        val interpreter = RuleInterpreter(client, profile)
        val manga = SManga.create().apply { url = "m1"; title = "Manga 1" }
        assertEquals("Manga 1", interpreter.details(manga).title)
        val chapter = SChapter.create().apply {
            url = "/manga/foo/chapter/1"; name = "Chapter 1"
            memo = kotlinx.serialization.json.buildJsonObject { put("mangatime.id", kotlinx.serialization.json.JsonPrimitive("remote-c1")) }
        }
        interpreter.pages(chapter)
        assertTrue(requests.any { it.url.encodedPath == "/details" && it.url.queryParameter("id") == "m1" })
        assertTrue(requests.any { it.url.encodedPath == "/pages" && it.url.queryParameter("id") == "remote-c1" && it.url.queryParameter("type") == "manga" })
    }
    @Test fun `legacy route only manga requires canonical route verification before learning remote ID`() = runBlocking<Unit> {
        val profile = rules().let { r -> r.copy(operations = r.operations + ("details" to r.operations.getValue("details").copy(fields = r.operations.getValue("details").fields + ("url" to FieldRule("url"))))) }
        val client = client(mutate = { request, body -> if (request.url.encodedPath == "/details") "{\"post\":{\"id\":\"m1\",\"url\":\"/manga/foo\",\"postTitle\":\"Manga 1\",\"featuredImage\":\"https://new.example/cover.jpg\"}}" else body })
        val manga = SManga.create().apply { url = "https://old.example/manga/foo"; title = "Manga 1" }
        val updated = RuleInterpreter(client, profile).details(manga)
        assertEquals(manga.url, updated.url)
        assertEquals("m1", updated.memo["id"]?.toString()?.trim('"'))
        try { RuleInterpreter(client, profile).details(manga.copy().apply { url = "/manga/unrelated" }); fail<Unit>("Must reject wrong canonical route") } catch (_: IOException) { }
    }

}
