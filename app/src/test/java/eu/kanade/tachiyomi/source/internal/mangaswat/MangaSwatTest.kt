package eu.kanade.tachiyomi.source.internal.mangaswat

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.ChapterFetchCompleteness
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mihon.domain.source.registry.DefaultInternalSourceRegistry
import mihon.domain.source.registry.DefaultSourceCollisionPolicy
import mihon.domain.source.registry.SourceOrigin
import mihon.domain.source.registry.SourcePreferenceMode
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

class MangaSwatTest {
    @org.junit.jupiter.api.BeforeEach
    fun setup() {
        val network = mockk<eu.kanade.tachiyomi.network.NetworkHelper>()
        every { network.defaultUserAgentProvider() } returns "Test Agent"
        uy.kohesive.injekt.Injekt.addSingletonFactory(uy.kohesive.injekt.api.fullType<eu.kanade.tachiyomi.network.NetworkHelper>()) { network }
    }

    private fun source(responder: (okhttp3.Request) -> String) = MangaSwat(
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(responder(chain.request()).toResponseBody()).build()
        }.build(),
    )
    private fun manga() = SManga.create().apply { url = "1624038"; title = "Sword Hound"; thumbnail_url = "https://meshmanga.com/cover.webp" }
    private fun chapter(id: Int) = """{"id":$id,"slug":"chapter-$id","chapter":"$id FREE","created_at":"2023-04-14T14:42:37Z"}"""
    private fun batch(ids: List<Int>, count: Int?, next: String? = null) =
        """{"results":[${ids.joinToString(",") { chapter(it) }}],${count?.let { "\"count\":$it," } ?: ""}"next":${next?.let { "\"$it\"" } ?: "null"}}"""

    @Test
    fun `pinned extension identity and collision fallback remain compatible`() {
        val internal = MangaSwat()
        internal.id shouldBe 7657007209499352344L
        internal.name shouldBe "MangaSwat"
        internal.lang shouldBe "ar"
        internal.versionId shouldBe 2
        internal.baseUrl shouldBe "https://meshmanga.com"
        internal.toString() shouldBe "MangaSwat (AR)"
        DefaultInternalSourceRegistry(listOf(internal)).getSources().single() shouldBe internal
        val external = mockk<Source> { every { id } returns internal.id }
        val policy = DefaultSourceCollisionPolicy()
        val collision = policy.resolveCollision(internal.id, internal, external, SourcePreferenceMode.EXTERNAL_PREFERRED)
        collision.selectedSource shouldBe external
        collision.fallbackSource shouldBe internal
        policy.resolveCollision(internal.id, internal, null, SourcePreferenceMode.EXTERNAL_PREFERRED).selectedOrigin shouldBe SourceOrigin.INTERNAL
    }

    @Test
    fun `catalogue details chapters preserve pinned numeric paths and remote memo`() {
        val source = MangaSwat()
        val manga = source.parseCatalogue("""{"results":[{"id":1624038,"title":"Sword Hound","poster":{"medium":"https://meshmanga.com/cover.webp"}}],"next":null}""").mangas.single()
        source.parseDetails("""{"id":1624038,"title":"Sword Hound","author":null,"artist":null,"genres":null}""", manga).url shouldBe "1624038"
        source.getMangaUrl(manga) shouldBe "https://meshmanga.com/series/1624038"
        val chapter = source.parseChapterBatch(batch(listOf(1624040), 1)).chapters.single()
        chapter.url shouldBe "/chapters/1624040/chapter-1624040/"
        chapter.memo shouldBe Json.parseToJsonElement("""{"id":1624040,"slug":"chapter-1624040"}""")
        source.getChapterUrl(chapter) shouldBe "https://meshmanga.com/chapter/1624040"
        chapter.date_upload shouldBe 1681483357000L
    }

    @Test
    fun `all continuation pages and advertised total are required for COMPLETE`(): Unit = runBlocking {
        val requests = mutableListOf<String>()
        val next = "https://appswat.com/v2/api/v2/chapters/?serie=1624038&page=2"
        val source = source { request ->
            requests.add(request.url.toString())
            if (request.url.queryParameter("page") == "2") batch(listOf(1), 3) else batch(listOf(3, 2), 3, next)
        }
        val result = source.getMangaUpdate(manga(), emptyList(), false, true)
        result.chapters.size shouldBe 3
        result.chapterCompleteness shouldBe ChapterFetchCompleteness.COMPLETE
        requests.size shouldBe 2
        requests.last() shouldBe next
    }

    @Test
    fun `missing proof is DEGRADED and truncation repeated IDs and cursor loops are clean errors`(): Unit = runBlocking {
        source { batch(listOf(1), null) }.getMangaUpdate(manga(), emptyList(), false, true).chapterCompleteness shouldBe ChapterFetchCompleteness.DEGRADED
        val next = "https://meshmanga.com/v2/api/v2/chapters/?serie=1624038&page=2"
        for (body in listOf(batch(listOf(1), 2), batch(listOf(1, 1), 2), batch(listOf(1), 2, next), """{"results":[],"count":2,"next":"$next"}""")) {
            assertThrows<IOException> { source { body }.getMangaUpdate(manga(), emptyList(), false, true) }
        }
    }

    @Test
    fun `wrong series cursors errors challenges and missing required fields fail cleanly`(): Unit = runBlocking {
        assertThrows<IOException> { source { batch(listOf(1), 2, "https://meshmanga.com/v2/api/v2/chapters/?serie=999&page=2") }.getMangaUpdate(manga(), emptyList(), false, true) }
        val source = MangaSwat()
        for (body in listOf("null", "<html>Just a moment...</html>", """{"detail":"Not found"}""", """{"results":[{"id":null,"title":"Invalid"}],"next":null}""", """{"results":[],"next":"https://invalid.example/next"}""")) {
            assertThrows<IOException> { source.parseCatalogue(body) }
        }
        assertThrows<IOException> { source.parsePages("""{"images_count":2,"images":[{"image":"https://meshmanga.com/1.webp"}]}""") }
        assertThrows<IOException> { source.parsePages("""{"images":[null]}""") }
        assertThrows<IOException> { source.parsePages("""{"images":null}""") }
    }
}
