package eu.kanade.domain.chapter.interactor

import eu.kanade.domain.chapter.model.copyFromSChapter
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.ChapterFetchCompleteness
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.chapter.ChapterSanitizer
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.service.ChapterIdentity
import tachiyomi.domain.chapter.model.NoChaptersException
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.chapter.service.ChapterRecognition
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.isLocal
import java.lang.Long.max
import kotlin.time.Clock

class SyncChaptersWithSource(
    private val downloadManager: DownloadManager,
    private val downloadProvider: DownloadProvider,
    private val chapterRepository: ChapterRepository,
    private val shouldUpdateDbChapter: ShouldUpdateDbChapter,
    private val updateManga: UpdateManga,
    private val updateChapter: UpdateChapter,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val getExcludedScanlators: GetExcludedScanlators,
    private val libraryPreferences: LibraryPreferences,
) {

    /**
     * Method to synchronize db chapters with source ones
     *
     * @param rawSourceChapters the chapters from the source.
     * @param manga the manga the chapters belong to.
     * @param source the source the manga belongs to.
     * @return Newly added chapters
     */
    suspend fun await(
        rawSourceChapters: List<SChapter>,
        manga: Manga,
        source: Source,
        manualFetch: Boolean = false,
        fetchWindow: Pair<Long, Long> = Pair(0, 0),
        completeness: ChapterFetchCompleteness = ChapterFetchCompleteness.DEGRADED,
    ): List<Chapter> {
        if (completeness == ChapterFetchCompleteness.FAILED) {
            throw java.io.IOException("Chapter fetch failed; existing chapters preserved")
        }
        if (rawSourceChapters.isEmpty() && !source.isLocal()) {
            throw NoChaptersException()
        }

        val timeZone = TimeZone.currentSystemDefault()
        val now = Clock.System.now().toLocalDateTime(timeZone)
        val nowMillis = now.toInstant(timeZone).toEpochMilliseconds()

        val sourceChapters = rawSourceChapters
            .distinctBy { it.url }
            .mapIndexed { i, sChapter ->
                Chapter.create()
                    .copyFromSChapter(sChapter)
                    .copy(name = with(ChapterSanitizer) { sChapter.name.sanitize(manga.title) })
                    .copy(mangaId = manga.id, sourceOrder = i.toLong())
            }

        val ids = sourceChapters.flatMap { ChapterIdentity.remoteIds(it, source.id) }
        if (ids.toSet().size != ids.size) throw java.io.IOException("Duplicate remote chapter identities; existing chapters preserved")
        val dbChapters = getChaptersByMangaId.await(manga.id)

        val newChapters = mutableListOf<Chapter>()
        val updatedChapters = mutableListOf<Chapter>()
        val identityUpdates = mutableListOf<ChapterUpdate>()
        var identityPlan = ChapterIdentity.reconcile(source.id, manga.source, manga.id, dbChapters, sourceChapters, allowFingerprint = completeness == ChapterFetchCompleteness.COMPLETE || source.isLocal())
        if (source is HttpSource && identityPlan.unresolved) {
            val matched = identityPlan.matches.values.map { it.id }.toSet()
            val redirects = ResolveChapterRedirects().await(source, dbChapters.filterNot { it.id in matched }, sourceChapters)
            if (redirects.isNotEmpty()) identityPlan = ChapterIdentity.reconcile(source.id, manga.source, manga.id, dbChapters, sourceChapters, redirects, allowFingerprint = completeness == ChapterFetchCompleteness.COMPLETE || source.isLocal())
        }
        if (identityPlan.unresolved) {
            mihon.domain.source.health.SourceHealthMonitor.shared.degrade(source.id)
            this.logcat(LogPriority.WARN) { "Degraded chapter reconciliation for manga ${manga.id}; unresolved rows preserved" }
        }
        val mayRemoveChapters = source.isLocal() || completeness == ChapterFetchCompleteness.COMPLETE && !identityPlan.unresolved
        val removedChapters = if (!mayRemoveChapters) emptyList() else dbChapters.filterNot { dbChapter ->
            sourceChapters.any { sourceChapter ->
                dbChapter.url == sourceChapter.url
            }
        }

        // Used to not set upload date of older chapters
        // to a higher value than newer chapters
        var maxSeenUploadDate = 0L

        for ((index, sourceChapter) in sourceChapters.withIndex()) {
            var chapter = sourceChapter

            // Update metadata from source if necessary.
            if (source is HttpSource) {
                val sChapter = chapter.toSChapter()
                @Suppress("DEPRECATION")
                source.prepareNewChapter(sChapter, manga.toSManga())
                chapter = chapter.copyFromSChapter(sChapter)
            }

            // Recognize chapter number for the chapter.
            val chapterNumber = ChapterRecognition.parseChapterNumber(manga.title, chapter.name, chapter.chapterNumber)
            chapter = chapter.copy(chapterNumber = chapterNumber)

            val dbChapter = identityPlan.matches[index]

            if (dbChapter == null) {
                if (index in identityPlan.blocked) continue
                val toAddChapter = if (chapter.dateUpload == 0L) {
                    val altDateUpload = if (maxSeenUploadDate == 0L) nowMillis else maxSeenUploadDate
                    chapter.copy(dateUpload = altDateUpload)
                } else {
                    maxSeenUploadDate = max(maxSeenUploadDate, sourceChapter.dateUpload)
                    chapter
                }
                newChapters.add(toAddChapter)
            } else if (dbChapter.url != chapter.url) {
                // Rename URL-hashed downloads first, then write only identity fields. Concurrent reading
                // state, history foreign keys, bookmark, dates and local ID remain untouched.
                val moved = dbChapter.copy(url = chapter.url, memo = kotlinx.serialization.json.JsonObject(dbChapter.memo + chapter.memo))
                MigrateChapterIdentity(chapterRepository, downloadManager).await(source, manga, dbChapter, moved)
                identityUpdates += ChapterUpdate(dbChapter.id, url = moved.url, memo = moved.memo)
            } else {
                if (shouldUpdateDbChapter.await(dbChapter, chapter)) {
                    val shouldRenameChapter = downloadProvider.isChapterDirNameChanged(dbChapter, chapter) &&
                        downloadManager.isChapterDownloaded(
                            dbChapter.name,
                            dbChapter.scanlator,
                            dbChapter.url,
                            manga.title,
                            manga.source,
                        )

                    if (shouldRenameChapter) {
                        downloadManager.renameChapter(source, manga, dbChapter, chapter)
                    }

                    var toChangeChapter = dbChapter.copy(
                        name = chapter.name,
                        chapterNumber = chapter.chapterNumber,
                        scanlator = chapter.scanlator,
                        sourceOrder = chapter.sourceOrder,
                        memo = kotlinx.serialization.json.JsonObject(dbChapter.memo + chapter.memo),
                    )

                    if (chapter.dateUpload != 0L) {
                        toChangeChapter = toChangeChapter.copy(dateUpload = chapter.dateUpload)
                    }
                    updatedChapters.add(toChangeChapter)
                }
            }
        }

        // Return if there's nothing to add, delete, or update to avoid unnecessary db transactions.
        if (newChapters.isEmpty() && removedChapters.isEmpty() && updatedChapters.isEmpty() && identityUpdates.isEmpty()) {
            if (manualFetch || manga.fetchInterval == 0 || manga.nextUpdate < fetchWindow.first) {
                updateManga.awaitUpdateFetchInterval(
                    manga,
                    timeZone,
                    now,
                    fetchWindow,
                )
            }
            return emptyList()
        }

        val changedOrDuplicateReadUrls = mutableSetOf<String>()

        val readChapterNumbers = dbChapters
            .asSequence()
            .filter { it.read && it.isRecognizedNumber }
            .map { it.chapterNumber }
            .toSet()

        val markDuplicateAsRead = libraryPreferences.markDuplicateReadChapterAsRead.get()
            .contains(LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_NEW)

        // Date fetch is set in such a way that the upper ones will have bigger value than the lower ones
        // Sources MUST return the chapters from most to less recent, which is common.
        var itemCount = newChapters.size
        var updatedToAdd = newChapters.map { toAddItem ->
            var chapter = toAddItem.copy(dateFetch = nowMillis + itemCount--)

            if (chapter.chapterNumber in readChapterNumbers && markDuplicateAsRead) {
                changedOrDuplicateReadUrls.add(chapter.url)
                chapter = chapter.copy(read = true)
            }


            chapter
        }

        val isChapterCountCollapse = !source.isLocal() &&
            dbChapters.size >= 5 &&
            sourceChapters.size < (dbChapters.size * 0.5)

        if (removedChapters.isNotEmpty() && !isChapterCountCollapse) {
            val toDeleteIds = removedChapters.map { it.id }
            chapterRepository.removeChaptersWithIds(toDeleteIds)
        } else if (isChapterCountCollapse) {
            this.logcat(LogPriority.WARN) {
                "Chapter collapse prevented for manga ${manga.id} (${manga.title}): DB had ${dbChapters.size} chapters, remote returned ${sourceChapters.size}. Preserving existing DB chapters."
            }
        }

        if (updatedToAdd.isNotEmpty()) {
            updatedToAdd = chapterRepository.addAll(updatedToAdd)
        }

        if (updatedChapters.isNotEmpty()) {
            val chapterUpdates = updatedChapters.map {
                ChapterUpdate(it.id, name = it.name, chapterNumber = it.chapterNumber, scanlator = it.scanlator,
                    sourceOrder = it.sourceOrder, dateUpload = it.dateUpload, memo = it.memo)
            }
            updateChapter.awaitAll(chapterUpdates)
        }
        updateManga.awaitUpdateFetchInterval(manga, timeZone, now, fetchWindow)

        // Set this manga as updated since chapters were changed
        // Note that last_update actually represents last time the chapter list changed at all
        updateManga.awaitUpdateLastUpdate(manga.id)

        val excludedScanlators = getExcludedScanlators.await(manga.id).toHashSet()

        return updatedToAdd.filterNot { it.url in changedOrDuplicateReadUrls || it.scanlator in excludedScanlators }
    }
}
