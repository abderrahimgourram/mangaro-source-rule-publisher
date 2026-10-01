package eu.kanade.domain.chapter.interactor

import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.chapter.service.ChapterIdentityLocks
import tachiyomi.domain.manga.model.Manga
import java.io.IOException

class MigrateChapterIdentity(private val repository: ChapterRepository, private val downloads: DownloadManager) {
    suspend fun await(source: Source, manga: Manga, old: Chapter, moved: Chapter): Chapter = ChapterIdentityLocks.forChapter(old.id).withLock {
        require(source.id == manga.source && old.mangaId == manga.id && moved.id == old.id)
        currentCoroutineContext().ensureActive()
        val current = repository.getChapterById(old.id) ?: throw IOException("Chapter no longer exists")
        if (current.url != old.url) throw IOException("Chapter identity changed concurrently; retry refresh")
        // Only this small local filesystem/SQL commit is non-cancellable, so cancellation cannot
        // strand downloads under a different URL hash. No network is performed inside it.
        withContext(NonCancellable) {
            val downloaded = downloads.isChapterDownloaded(current.name, current.scanlator, current.url, manga.title, manga.source)
            val target = current.copy(url = moved.url, memo = moved.memo)
            if (downloaded && current.url != target.url) {
                downloads.renameChapter(source, manga, current, target)
                if (!downloads.isChapterDownloaded(target.name, target.scanlator, target.url, manga.title, manga.source, skipCache = true)) {
                    throw IOException("Chapter download migration failed; old identity preserved")
                }
            }
            try {
                repository.update(ChapterUpdate(current.id, url = target.url, memo = target.memo))
            } catch (e: Exception) {
                if (downloaded && current.url != target.url) downloads.renameChapter(source, manga, target, current)
                throw e
            }
            target
        }
    }
}
