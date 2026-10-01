package tachiyomi.domain.chapter.service

import kotlinx.coroutines.sync.Mutex

/** Bounded striped locks shared with downloads: never rename a URL-hashed directory mid-download. */
object ChapterIdentityLocks {
    private val locks = Array(64) { Mutex() }
    fun forChapter(id: Long): Mutex = locks[(id and 63).toInt()]
}
