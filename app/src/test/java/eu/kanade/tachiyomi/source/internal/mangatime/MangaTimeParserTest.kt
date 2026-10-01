package eu.kanade.tachiyomi.source.internal.mangatime

import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.fullType

class MangaTimeParserTest {

    private lateinit var mangaTime: MangaTime

    @BeforeEach
    fun setUp() {
        Injekt.addSingletonFactory(fullType<Json>()) {
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
        }
        mangaTime = MangaTime()
    }

    @Test
    fun `verify MangaTime source ID matches pinned baseline exact value`() {
        mangaTime.id shouldBe 215553151312092548L
        mangaTime.name shouldBe "MangaTime"
        mangaTime.lang shouldBe "ar"
        mangaTime.versionId shouldBe 1
    }

    @Test
    fun `verify popularMangaRequest builds non-batch tRPC input string`() {
        val req = mangaTime.popularMangaRequest(1)
        val url = req.url.toString()

        url.contains("search.searchSeries") shouldBe true
        url.contains("input=") shouldBe true
        // Must contain non-batch json key, NOT {"0":{"json":...}}
        url.contains("%7B%22json%22%3A") shouldBe true
        url.contains("%7B%220%22%3A") shouldBe false
    }

    @Test
    fun `verify parseSeriesSearchResponse correctly parses non-batch tRPC series list`() {
        val jsonResponse = """
            {
              "result": {
                "data": {
                  "json": {
                    "results": [
                      {
                        "id": "697df7820c5d340ac154519f",
                        "title": "بلو لوك",
                        "slug": "blue-lock",
                        "coverUrl": "https://mangatime.org/uploads/cover.jpg",
                        "type": "manga"
                      }
                    ],
                    "hasMore": true
                  }
                }
              }
            }
        """.trimIndent()

        val mangasPage = mangaTime.parseSeriesSearchResponse(jsonResponse)
        mangasPage.hasNextPage shouldBe true
        mangasPage.mangas.size shouldBe 1

        val manga = mangasPage.mangas.first()
        manga.title shouldBe "بلو لوك"
        manga.url shouldBe "/manga/blue-lock#697df7820c5d340ac154519f"
        manga.thumbnail_url shouldBe "https://mangatime.org/uploads/cover.jpg"
    }

    @Test
    fun `verify parseMangaDetailsResponse correctly extracts series metadata and ID`() {
        val initialManga = SManga.create().apply {
            url = "/manga/blue-lock#697df7820c5d340ac154519f"
            title = "Blue Lock"
        }

        val jsonResponse = """
            {
              "result": {
                "data": {
                  "json": {
                    "id": "697df7820c5d340ac154519f",
                    "title": "بلو لوك",
                    "description": "Awesome soccer manga",
                    "coverUrl": "https://mangatime.org/uploads/cover.jpg",
                    "status": "ongoing"
                  }
                }
              }
            }
        """.trimIndent()

        val updated = mangaTime.parseMangaDetailsResponse(jsonResponse, initialManga, "manga", "blue-lock", "697df7820c5d340ac154519f")
        updated.title shouldBe "بلو لوك"
        updated.description shouldBe "Awesome soccer manga"
        updated.thumbnail_url shouldBe "https://mangatime.org/uploads/cover.jpg"
        updated.status shouldBe SManga.ONGOING
        updated.url shouldBe "/manga/blue-lock#697df7820c5d340ac154519f"
    }

    @Test
    fun `verify parseChaptersResponse correctly extracts chapter list`() {
        val jsonResponse = """
            {
              "result": {
                "data": {
                  "json": {
                    "chapters": [
                      {
                        "id": "ch1",
                        "number": 363,
                        "title": "الفصل 363: لعبة المحظورات"
                      },
                      {
                        "id": "ch2",
                        "number": 362
                      }
                    ]
                  }
                }
              }
            }
        """.trimIndent()

        val chapters = mangaTime.parseChaptersResponse(jsonResponse, "manga", "blue-lock")
        chapters.size shouldBe 2

        chapters[0].name shouldBe "الفصل 363: لعبة المحظورات"
        chapters[0].url shouldBe "/manga/blue-lock/chapter/363"
        chapters[0].chapter_number shouldBe 363.0f

        chapters[1].name shouldBe "الفصل 362"
        chapters[1].url shouldBe "/manga/blue-lock/chapter/362"
        chapters[1].chapter_number shouldBe 362.0f
    }

    @Test
    fun `verify parseChapterPagesResponse correctly parses image URLs`() {
        val jsonResponse = """
            {
              "result": {
                "data": {
                  "json": {
                    "pages": [
                      "https://mangatime.org/uploads/chapters/ch1/001.webp",
                      "https://mangatime.org/uploads/chapters/ch1/002.webp"
                    ]
                  }
                }
              }
            }
        """.trimIndent()

        val pages = mangaTime.parseChapterPagesResponse(jsonResponse)
        pages.size shouldBe 2
        pages[0].imageUrl shouldBe "https://mangatime.org/uploads/chapters/ch1/001.webp"
        pages[1].imageUrl shouldBe "https://mangatime.org/uploads/chapters/ch1/002.webp"
    }
    @Test
    fun `search query is encoded as JSON rather than interpolated JSON syntax`() {
        val query = "quoted \"title\" \\ path"
        val request = mangaTime.searchMangaRequest(1, query, eu.kanade.tachiyomi.source.model.FilterList())
        val input = kotlinx.serialization.json.Json.parseToJsonElement(request.url.queryParameter("input")!!)
        input.jsonObject["json"]!!.jsonObject["query"]!!.jsonPrimitive.content shouldBe query
    }

    @Test
    fun `null optional metadata preserves catalogue data and required null envelopes fail cleanly`() {
        val manga = SManga.create().apply { title = "Existing"; description = "Description"; thumbnail_url = "https://example.org/cover.jpg" }
        val details = mangaTime.parseMangaDetailsResponse("""{"result":{"data":{"json":{"id":"42","title":null,"description":null,"coverUrl":null,"status":null}}}}""", manga, "manhwa", "sample", "42")
        details.title shouldBe "Existing"
        details.description shouldBe "Description"
        details.thumbnail_url shouldBe "https://example.org/cover.jpg"
        for (body in listOf("null", "[null]", "{\"result\":null}", "{\"result\":{\"data\":{\"json\":null}}}")) {
            org.junit.jupiter.api.assertThrows<java.io.IOException> { mangaTime.parseMangaDetailsResponse(body, manga, "manhwa", "sample", "42") }
            org.junit.jupiter.api.assertThrows<java.io.IOException> { mangaTime.parseChaptersResponse(body, "manhwa", "sample") }
            org.junit.jupiter.api.assertThrows<java.io.IOException> { mangaTime.parseChapterPagesResponse(body) }
        }
        org.junit.jupiter.api.assertThrows<java.io.IOException> { mangaTime.parseSeriesSearchResponse("""{"result":{"data":{"json":{"results":[{"id":null,"slug":"sample"}]}}}}""") }
    }

}
