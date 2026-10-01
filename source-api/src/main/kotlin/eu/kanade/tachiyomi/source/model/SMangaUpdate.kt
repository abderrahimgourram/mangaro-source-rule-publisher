package eu.kanade.tachiyomi.source.model

/** Only COMPLETE authorizes removal of chapters absent from a remote result. */
enum class ChapterFetchCompleteness {
    COMPLETE,
    PARTIAL,
    DEGRADED,
    FAILED,
}

@Suppress("UNUSED")
class SMangaUpdate(val manga: SManga, val chapters: List<SChapter>) {
    // Keep the two-argument JVM constructor for installed extensions. Legacy results are
    // unverified and can add/update chapters, but cannot authorize destructive reconciliation.
    var chapterCompleteness: ChapterFetchCompleteness = ChapterFetchCompleteness.DEGRADED
        private set

    constructor(
        manga: SManga,
        chapters: List<SChapter>,
        chapterCompleteness: ChapterFetchCompleteness,
    ) : this(manga, chapters) {
        this.chapterCompleteness = chapterCompleteness
    }
}
