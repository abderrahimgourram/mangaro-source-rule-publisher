package eu.kanade.domain.chapter.interactor

import eu.kanade.domain.chapter.model.copyFromSChapter
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.chapter.service.ChapterIdentity
import tachiyomi.domain.manga.model.Manga
import java.io.IOException

/** One refresh and one validated page-list retry. Never sync/delete the whole list from Reader. */
class RecoverStaleChapter(
    private val chapters: ChapterRepository,
    private val downloads: DownloadManager,
    private val health: mihon.domain.source.health.SourceHealthMonitor = mihon.domain.source.health.SourceHealthMonitor.shared,
) {
    data class Recovery(val chapter: Chapter, val pages: List<Page>)

    suspend fun await(source: HttpSource, manga: Manga, stored: Chapter, error: Throwable): Recovery? {
        if (!isRecoverable(error) || source.id != manga.source || stored.mangaId != manga.id) return null
        return withTimeoutOrNull(90_000) {
            val existing = chapters.getChapterByMangaId(manga.id)
            val current = existing.singleOrNull { it.id == stored.id } ?: return@withTimeoutOrNull null
            val update = health.run(source.id, 60_000, healthy = {
                it.chapterCompleteness !in setOf(eu.kanade.tachiyomi.source.model.ChapterFetchCompleteness.PARTIAL, eu.kanade.tachiyomi.source.model.ChapterFetchCompleteness.FAILED)
            }) { source.getMangaUpdate(manga.toSManga(), existing.map { it.toSChapter() }, false, true) }
            if (update.chapterCompleteness == eu.kanade.tachiyomi.source.model.ChapterFetchCompleteness.FAILED) return@withTimeoutOrNull null
            val incoming = update.chapters.map { Chapter.create().copyFromSChapter(it).copy(mangaId = manga.id) }
            var plan = ChapterIdentity.reconcile(source.id, manga.source, manga.id, existing, incoming, allowFingerprint = update.chapterCompleteness == eu.kanade.tachiyomi.source.model.ChapterFetchCompleteness.COMPLETE)
            var index = plan.matches.entries.singleOrNull { it.value.id == current.id }?.key
            if (index == null) {
                val redirects = ResolveChapterRedirects().await(source, listOf(current), incoming)
                if (redirects.isNotEmpty()) {
                    plan = ChapterIdentity.reconcile(source.id, manga.source, manga.id, existing, incoming, redirects, allowFingerprint = update.chapterCompleteness == eu.kanade.tachiyomi.source.model.ChapterFetchCompleteness.COMPLETE)
                    index = plan.matches.entries.singleOrNull { it.value.id == current.id }?.key
                }
            }
            val remote = index?.let { incoming[it] } ?: return@withTimeoutOrNull null
            val memo = JsonObject(current.memo + remote.memo)
            if (remote.url == current.url && memo == current.memo) return@withTimeoutOrNull null
            // Validate before writing; one failed retry cannot corrupt the stored URL.
            val pages = source.getPageList(remote.toSChapter())
            if (pages.isEmpty()) throw IOException("Recovered chapter has no pages")
            val moved = current.copy(url = remote.url, memo = memo)
            val saved = MigrateChapterIdentity(chapters, downloads).await(source, manga, current, moved)
            Recovery(saved, pages)
        }
    }

    companion object {
        fun isRecoverable(error: Throwable): Boolean = when (error) {
            is HttpException -> error.code in setOf(404, 410, 403)
            is IOException -> error.message.orEmpty().lowercase().let {
                it.contains("chapter") && (it.contains("not found") || it.contains("expired") || it.contains("missing") || it.contains("no pages"))
            }
            else -> false
        }
    }
}
