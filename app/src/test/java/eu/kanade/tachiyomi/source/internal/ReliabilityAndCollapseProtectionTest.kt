package eu.kanade.tachiyomi.source.internal

import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.internal.util.SourceValidationUtil
import eu.kanade.tachiyomi.source.model.ChapterFetchCompleteness
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.SChapter
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.isLocal
import java.io.IOException

class ReliabilityAndCollapseProtectionTest {

    private lateinit var downloadManager: DownloadManager
    private lateinit var downloadProvider: DownloadProvider
    private lateinit var chapterRepository: ChapterRepository
    private lateinit var shouldUpdateDbChapter: ShouldUpdateDbChapter
    private lateinit var updateManga: UpdateManga
    private lateinit var updateChapter: UpdateChapter
    private lateinit var getChaptersByMangaId: GetChaptersByMangaId
    private lateinit var getExcludedScanlators: GetExcludedScanlators
    private lateinit var libraryPreferences: LibraryPreferences

    private lateinit var syncChaptersWithSource: SyncChaptersWithSource

    @BeforeEach
    fun setUp() {
        downloadManager = mockk(relaxed = true)
        downloadProvider = mockk(relaxed = true)
        chapterRepository = mockk(relaxed = true)
        shouldUpdateDbChapter = mockk(relaxed = true)
        updateManga = mockk(relaxed = true)
        updateChapter = mockk(relaxed = true)
        getChaptersByMangaId = mockk(relaxed = true)
        getExcludedScanlators = mockk(relaxed = true)
        libraryPreferences = mockk(relaxed = true)

        coEvery { getExcludedScanlators.await(any()) } returns emptySet()
        coEvery { libraryPreferences.markDuplicateReadChapterAsRead.get() } returns emptySet()

        syncChaptersWithSource = SyncChaptersWithSource(
            downloadManager,
            downloadProvider,
            chapterRepository,
            shouldUpdateDbChapter,
            updateManga,
            updateChapter,
            getChaptersByMangaId,
            getExcludedScanlators,
            libraryPreferences,
        )
    }

    @Test
    fun `verify SourceValidationUtil throws IOException when Cloudflare challenge is present`() {
        val html = "<html><head><title>Just a moment...</title></head><body><div class='challenge-running'>Checking browser</div></body></html>"
        val doc = Jsoup.parse(html)

        assertThrows<IOException> {
            SourceValidationUtil.checkCloudflareOrError(doc)
        }
    }

    @Test
    fun `verify chapter collapse protection prevents deleting existing DB chapters when remote returns partial list`() = runTest {
        val manga = Manga.create().copy(id = 100L, source = 100L, title = "Test Manga")
        val source: Source = mockk(relaxed = true)
        coEvery { source.id } returns 100L

        // Existing DB has 100 chapters
        val dbChapters = (1..100).map { i ->
            Chapter.create().copy(
                id = i.toLong(),
                mangaId = 100L,
                url = "/chapter-$i",
                name = "Chapter $i",
                chapterNumber = i.toDouble(),
            )
        }
        coEvery { getChaptersByMangaId.await(100L) } returns dbChapters

        // Remote returns only 10 chapters due to pagination failure (10% of DB list -> collapse!)
        val remoteChapters = (1..10).map { i ->
            SChapter.create().apply {
                url = "/chapter-$i"
                name = "Chapter $i"
                chapter_number = i.toFloat()
            }
        }

        syncChaptersWithSource.await(
            rawSourceChapters = remoteChapters,
            manga = manga,
            source = source,
        )

        // Verify chapterRepository.removeChaptersWithIds is NEVER called!
        coVerify(exactly = 0) { chapterRepository.removeChaptersWithIds(any()) }
    }

    @Test
    fun `complete list preserves unmatched old chapters without removal proof`() = runTest {
        val manga = Manga.create().copy(id = 100L, source = 100L, title = "Test Manga")
        val source: Source = mockk(relaxed = true)
        coEvery { source.id } returns 100L

        // Existing DB has 10 chapters
        val dbChapters = (1..10).map { i ->
            Chapter.create().copy(
                id = i.toLong(),
                mangaId = 100L,
                url = "/chapter-$i",
                name = "Chapter $i",
                chapterNumber = i.toDouble(),
            )
        }
        coEvery { getChaptersByMangaId.await(100L) } returns dbChapters

        // Remote returns 9 chapters (1 removed legitimately -> 90% >= 50%)
        val remoteChapters = (1..9).map { i ->
            SChapter.create().apply {
                url = "/chapter-$i"
                name = "Chapter $i"
                chapter_number = i.toFloat()
            }
        }

        syncChaptersWithSource.await(
            rawSourceChapters = remoteChapters,
            manga = manga,
            source = source,
            completeness = ChapterFetchCompleteness.COMPLETE,
        )

        // Absence alone cannot distinguish removal from a URL move without stable identity.
        coVerify(exactly = 0) { chapterRepository.removeChaptersWithIds(any()) }
    }
    @Test
    fun `a ninety percent partial list cannot delete chapters above the old collapse threshold`() = runTest {
        val manga = Manga.create().copy(id = 100L, source = 100L, title = "Test Manga")
        val source: Source = mockk(relaxed = true)
        coEvery { source.id } returns 100L
        coEvery { getChaptersByMangaId.await(100L) } returns (1..100).map {
            Chapter.create().copy(id = it.toLong(), mangaId = 100L, url = "/chapter-$it", name = "Chapter $it", chapterNumber = it.toDouble())
        }
        val remote = (1..90).map { SChapter.create().apply { url = "/chapter-$it"; name = "Chapter $it" } }
        for (state in listOf(ChapterFetchCompleteness.PARTIAL, ChapterFetchCompleteness.DEGRADED)) {
            syncChaptersWithSource.await(remote, manga, source, completeness = state)
        }
        assertThrows<IOException> {
            syncChaptersWithSource.await(remote, manga, source, completeness = ChapterFetchCompleteness.FAILED)
        }
        coVerify(exactly = 0) { chapterRepository.removeChaptersWithIds(any()) }
    }

    @Test
    fun `legacy two argument update is explicitly unverified`() {
        SMangaUpdate(SManga.create(), emptyList()).chapterCompleteness shouldBe ChapterFetchCompleteness.DEGRADED
    }

    @Test
    fun `same chapter number cannot transfer identity state to a new URL`() = runTest {
        val manga = Manga.create().copy(id = 100L, source = 100L, title = "Test Manga")
        val source: Source = mockk(relaxed = true)
        coEvery { source.id } returns 100L
        coEvery { getChaptersByMangaId.await(100L) } returns listOf(
            Chapter.create().copy(id = 7L, mangaId = 100L, url = "/old", name = "Chapter 1", chapterNumber = 1.0, read = true, bookmark = true),
        )
        coEvery { chapterRepository.addAll(any()) } answers { firstArg() }
        val remote = SChapter.create().apply { url = "/other-remote-id"; name = "Chapter 1"; chapter_number = 1f }
        val added = syncChaptersWithSource.await(listOf(remote), manga, source, completeness = ChapterFetchCompleteness.COMPLETE)
        added shouldBe emptyList()
        coVerify(exactly = 0) { chapterRepository.addAll(any()) }
        coVerify(exactly = 0) { chapterRepository.removeChaptersWithIds(any()) }
    }



    @Test
    fun `stable remote identity updates only URL and memo without inserting deleting or rewriting reading state`() = runTest {
        val manga = Manga.create().copy(id = 100, source = 100, title = "Test Manga")
        val source = mockk<Source>(relaxed = true)
        coEvery { source.id } returns 100
        val memo = kotlinx.serialization.json.buildJsonObject { put("id", kotlinx.serialization.json.JsonPrimitive("88")) }
        val old = Chapter.create().copy(id = 7, mangaId = 100, url = "/old", memo = memo, read = true, bookmark = true, lastPageRead = 6, dateFetch = 123, name = "Chapter 1")
        coEvery { getChaptersByMangaId.await(100) } returns listOf(old)
        coEvery { chapterRepository.getChapterById(7) } returns old
        val remote = SChapter.create().apply { url = "/new"; name = "Chapter 1"; this.memo = memo }
        syncChaptersWithSource.await(listOf(remote), manga, source, completeness = ChapterFetchCompleteness.PARTIAL)
        coVerify(exactly = 1) { chapterRepository.update(tachiyomi.domain.chapter.model.ChapterUpdate(7, url = "/new", memo = memo)) }
        coVerify(exactly = 0) { chapterRepository.addAll(any()) }
        coVerify(exactly = 0) { chapterRepository.removeChaptersWithIds(any()) }
    }

    @Test
    fun `ambiguous descriptive fingerprint is retained without automatic merge or duplicate insertion`() = runTest {
        val manga = Manga.create().copy(id = 100, source = 100, title = "Test Manga")
        val source = mockk<Source>(relaxed = true)
        coEvery { source.id } returns 100
        coEvery { getChaptersByMangaId.await(100) } returns (1L..2L).map { Chapter.create().copy(id = it, mangaId = 100, url = "/old-$it", name = "Chapter 1: Beginning", chapterNumber = 1.0) }
        val remote = SChapter.create().apply { url = "/new"; name = "Chapter 1: Beginning"; chapter_number = 1f }
        syncChaptersWithSource.await(listOf(remote), manga, source, completeness = ChapterFetchCompleteness.COMPLETE)
        coVerify(exactly = 0) { chapterRepository.addAll(any()) }
        coVerify(exactly = 0) { chapterRepository.updateAll(any()) }
        coVerify(exactly = 0) { chapterRepository.removeChaptersWithIds(any()) }
    }
}
