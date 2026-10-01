package eu.kanade.domain.chapter.interactor

import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.Source
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import java.io.IOException

class MigrateChapterIdentityTest {
    @Test fun `existing download is renamed before SQL and rollback preserves old relationship on SQL error`() = runTest {
        val repo = mockk<ChapterRepository>(relaxed = true)
        val downloads = mockk<DownloadManager>(relaxed = true)
        val source = mockk<Source> { every { id } returns 44 }
        val manga = Manga.create().copy(id = 10, source = 44, title = "manga")
        val old = Chapter.create().copy(id = 7, mangaId = 10, url = "/old", name = "chapter")
        val moved = old.copy(url = "/new")
        coEvery { repo.getChapterById(7) } returns old
        every { downloads.isChapterDownloaded(any(), any(), any(), any(), any(), any()) } returns true
        coEvery { repo.update(any()) } throws IOException("SQL failed")
        assertThrows<IOException> { MigrateChapterIdentity(repo, downloads).await(source, manga, old, moved) }
        coVerifyOrder {
            downloads.renameChapter(source, manga, old, moved)
            repo.update(any())
            downloads.renameChapter(source, manga, moved, old)
        }
    }
}
