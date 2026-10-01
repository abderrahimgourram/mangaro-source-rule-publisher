package eu.kanade.tachiyomi.source

import eu.kanade.domain.chapter.model.copyFromSChapter
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.domain.manga.model.toDomainManga
import org.junit.jupiter.api.Test
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.chapter.model.Chapter

class SourceCompatibilityBaselineTest {
    private val fixture = Json.parseToJsonElement(
        checkNotNull(javaClass.getResourceAsStream("/source-compatibility/upstream_pinned_cases.json"))
            .bufferedReader().use { it.readText() },
    ).jsonObject
    private val sources = fixture.getValue("sources") as JsonArray

    @Test
    fun `pinned upstream source ids match this app source API`() {
        sources.forEach { entry ->
            val item = entry.jsonObject
            val source = IdentitySource(
                sourceName = item.getValue("name").jsonPrimitive.content,
                sourceLang = item.getValue("lang").jsonPrimitive.content,
                sourceVersion = item.getValue("versionId").jsonPrimitive.int,
            )
            source.id shouldBe item.getValue("sourceId").jsonPrimitive.content.toLong()
        }
    }

    @Test
    fun `Azora and MangaTime manga memo survive source to domain to SQL adapter to source`() {
        sources.filter { it.jsonObject.getValue("key").jsonPrimitive.content in setOf("azora", "mangatime") }
            .forEach { entry ->
                val item = entry.jsonObject
                val expectedMemo = item.getValue("mangaMemo").jsonObject
                val sourceManga = SManga.create().apply {
                    url = item.getValue("mangaUrl").jsonPrimitive.content
                    title = "Public fixture"
                    memo = expectedMemo
                }
                val stored = sourceManga.toDomainManga(item.getValue("sourceId").jsonPrimitive.content.toLong())
                val reloaded = stored.copy(memo = MemoColumnAdapter.decode(MemoColumnAdapter.encode(stored.memo)))
                reloaded.toSManga().url shouldBe sourceManga.url
                reloaded.toSManga().memo shouldBe expectedMemo
            }
    }

    @Test
    fun `Azora chapter remote id survives source domain SQL adapter and memo-only update detection`() {
        val item = sources.first { it.jsonObject.getValue("key").jsonPrimitive.content == "azora" }.jsonObject
        val expectedMemo = item.getValue("chapterMemo").jsonObject
        val sourceChapter = SChapter.create().apply {
            url = item.getValue("chapterUrl").jsonPrimitive.content
            name = "Chapter 68"
            memo = expectedMemo
        }
        val domain = Chapter.create().copyFromSChapter(sourceChapter)
        val reloaded = domain.copy(memo = MemoColumnAdapter.decode(MemoColumnAdapter.encode(domain.memo)))
        reloaded.toSChapter().url shouldBe sourceChapter.url
        reloaded.toSChapter().memo shouldBe expectedMemo
        ShouldUpdateDbChapter().await(reloaded.copy(memo = JsonObject(emptyMap())), reloaded) shouldBe true
    }

    private class IdentitySource(
        private val sourceName: String,
        private val sourceLang: String,
        private val sourceVersion: Int,
    ) : HttpSource() {
        override val name = sourceName
        override val lang = sourceLang
        override val baseUrl = "https://example.invalid"
        override val versionId = sourceVersion
        override val supportsLatest = false
    }
}
