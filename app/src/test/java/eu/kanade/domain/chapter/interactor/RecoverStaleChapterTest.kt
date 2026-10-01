package eu.kanade.domain.chapter.interactor

import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.model.ChapterFetchCompleteness
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga

class RecoverStaleChapterTest {
    private val health = mihon.domain.source.health.SourceHealthMonitor()
    private val old = Chapter.create().copy(id = 7, mangaId = 10, url = "/old#11", name = "chapter 1", chapterNumber = 1.0,
        read = true, bookmark = true, lastPageRead = 8, dateFetch = 500, memo = buildJsonObject { put("id", "11") })
    private val manga = Manga.create().copy(id = 10, source = 44, title = "manga")
    private val repository = mockk<ChapterRepository>(relaxed = true)
    private val downloads = mockk<DownloadManager>(relaxed = true)
    private val source = mockk<HttpSource>(relaxed = true)
    private val moved = old.copy(url = "/new#11")
    private fun prepare() {
        every { source.id } returns 44
        coEvery { repository.getChapterByMangaId(10) } returns listOf(old)
        coEvery { repository.getChapterById(7) } returns old
        coEvery { source.getMangaUpdate(any(), any(), any(), any()) } returns SMangaUpdate(SManga.create(), listOf(moved.toSChapter()), ChapterFetchCompleteness.COMPLETE)
        coEvery { source.getPageList(any()) } returns listOf(Page(0, "", "https://image/1.jpg"))
    }
    @Test fun `Reader 404 retries moved URL once and writes only identity preserving history FK and states`() = runTest {
        prepare()
        val result = RecoverStaleChapter(repository, downloads, health).await(source, manga, old, HttpException(404))!!
        result.chapter.id shouldBe 7
        result.chapter.read shouldBe true
        result.chapter.bookmark shouldBe true
        result.chapter.lastPageRead shouldBe 8
        result.chapter.dateFetch shouldBe 500
        result.chapter.url shouldBe "/new#11"
        coVerify(exactly = 1) { source.getMangaUpdate(any(), any(), false, true) }
        coVerify(exactly = 1) { source.getPageList(match { it.url == "/new#11" }) }
        coVerify(exactly = 1) { repository.update(ChapterUpdate(7, url = moved.url, memo = old.memo)) }
        coVerify(exactly = 0) { repository.addAll(any()) }
        coVerify(exactly = 0) { repository.removeChaptersWithIds(any()) }
    }
    @Test fun `permanent failure is never refreshed and failed retry never updates stored identity`() = runTest {
        prepare()
        RecoverStaleChapter(repository, downloads, health).await(source, manga, old, HttpException(500)) shouldBe null
        coVerify(exactly = 0) { source.getMangaUpdate(any(), any(), any(), any()) }
        coEvery { source.getPageList(any()) } throws HttpException(404)
        assertThrows<HttpException> { RecoverStaleChapter(repository, downloads, health).await(source, manga, old, HttpException(404)) }
        coVerify(exactly = 1) { source.getPageList(any()) }
        coVerify(exactly = 0) { repository.update(any()) }
    }
    @Test fun `failed completeness never recovers and another source cannot touch this row`() = runTest {
        prepare()
        coEvery { source.getMangaUpdate(any(), any(), any(), any()) } returns SMangaUpdate(SManga.create(), listOf(moved.toSChapter()), ChapterFetchCompleteness.FAILED)
        RecoverStaleChapter(repository, downloads, health).await(source, manga, old, HttpException(404)) shouldBe null
        RecoverStaleChapter(repository, downloads, health).await(source, manga.copy(source = 55), old, HttpException(404)) shouldBe null
        coVerify(exactly = 0) { repository.update(any()) }
    }
}
