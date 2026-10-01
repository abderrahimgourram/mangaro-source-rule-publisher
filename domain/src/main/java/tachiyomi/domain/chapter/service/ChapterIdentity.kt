package tachiyomi.domain.chapter.service

import kotlinx.serialization.json.JsonPrimitive
import tachiyomi.domain.chapter.model.Chapter

/** Identities are meaningful only inside one source and one manga. No number-only matching. */
object ChapterIdentity {
    data class Plan(val matches: Map<Int, Chapter>, val unresolved: Boolean, val blocked: Set<Int>)

    fun remoteIds(chapter: Chapter, sourceId: Long? = null): Set<String> {
        val memoIds = chapter.memo.entries.mapNotNull { (key, value) ->
            if (key == "id" || key == "chapterId" || key == "remoteId" || key.endsWith(".id")) {
                (value as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
            } else null
        }.toSet()
        if (memoIds.isNotEmpty()) return memoIds
        // Pinned Azora URLs explicitly encode the remote ID in the fragment, including legacy rows
        // created before chapter memo support. Never interpret a generic chapter number as an ID.
        if (sourceId == 2482399499047903203L) {
            chapter.url.substringAfter('#', "").takeIf { it.toLongOrNull() != null }?.let { return setOf(it) }
        }
        return emptySet()
    }

    fun reconcile(
        sourceId: Long,
        mangaSourceId: Long,
        mangaId: Long,
        existing: List<Chapter>,
        incoming: List<Chapter>,
        verifiedRedirects: Map<String, String> = emptyMap(),
        allowFingerprint: Boolean = true,
    ): Plan {
        require(sourceId == mangaSourceId && existing.all { it.mangaId == mangaId } && incoming.all { it.mangaId == mangaId })
        val matches = mutableMapOf<Int, Chapter>()
        val used = mutableSetOf<Long>()
        var ambiguous = false
        val blocked = mutableSetOf<Int>()
        fun match(predicate: (Chapter, Chapter) -> Boolean) {
            val candidates = incoming.indices.filterNot { it in matches }.associateWith { index ->
                existing.filter { it.id !in used && predicate(it, incoming[index]) }
            }
            for ((index, old) in candidates) {
                if (old.size == 1 && candidates.values.count { old.single() in it } == 1) {
                    matches[index] = old.single()
                    used += old.single().id
                } else if (old.isNotEmpty()) { ambiguous = true; blocked += index }
            }
        }
        match { old, new -> old.url == new.url }
        match { old, new -> remoteIds(old, sourceId).intersect(remoteIds(new, sourceId)).isNotEmpty() }
        match { old, new ->
            verifiedRedirects[old.url] == new.url &&
                (old.chapterNumber < 0 || new.chapterNumber < 0 || old.chapterNumber == new.chapterNumber) &&
                (old.scanlator.isNullOrBlank() || new.scanlator.isNullOrBlank() || old.scanlator == new.scanlator)
        }
        match { old, new ->
            val oldIds = remoteIds(old, sourceId)
            val newIds = remoteIds(new, sourceId)
            // Conflicting remote identities are stronger evidence than a similar title/number.
            allowFingerprint && (oldIds.isEmpty() || newIds.isEmpty()) && fingerprint(old)?.let { it == fingerprint(new) } == true
        }
        for (index in incoming.indices.filterNot { it in matches }) {
            val new = incoming[index]
            if (existing.any { old -> old.id !in used &&
                (remoteIds(old, sourceId).isEmpty() || remoteIds(new, sourceId).isEmpty()) &&
                old.chapterNumber >= 0 && old.chapterNumber == new.chapterNumber &&
                old.name.trim().equals(new.name.trim(), ignoreCase = true) && old.scanlator.orEmpty() == new.scanlator.orEmpty()
            }) blocked += index
        }
        // An absent old row can be a moved chapter. Without proof, retain it even for COMPLETE lists.
        return Plan(matches, ambiguous || existing.any { it.id !in used }, blocked - matches.keys)
    }

    private fun fingerprint(chapter: Chapter): String? {
        if (!chapter.chapterNumber.isFinite() || chapter.chapterNumber < 0) return null
        val name = chapter.name.lowercase().replace(Regex("\\s+"), " ").trim()
        val descriptive = name.replace(Regex("(?i)chapter|الفصل|فصل|[\\d\\s\\p{Punct}]+"), "").trim()
        if (descriptive.length < 4) return null
        return "${chapter.chapterNumber}|$name|${chapter.scanlator.orEmpty().trim().lowercase()}"
    }
}
