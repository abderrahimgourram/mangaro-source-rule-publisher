package tachiyomi.domain.chapter.service

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tachiyomi.domain.chapter.model.Chapter

class ChapterIdentityTest {
    private fun row(id: Long, url: String, remote: String? = null) = Chapter.create().copy(
        id = id, mangaId = 10, url = url, chapterNumber = 1.0, name = "Chapter 1: The beginning", scanlator = "team",
        memo = buildJsonObject { remote?.let { put("azora.id", it) } },
    )
    private fun plan(old: List<Chapter>, new: List<Chapter>) = ChapterIdentity.reconcile(4, 4, 10, old, new)
    @Test fun `same remote identity changes URL without changing the local row`() {
        val old = row(12, "/old#123", "123").copy(read = true, bookmark = true, lastPageRead = 9)
        val match = plan(listOf(old), listOf(row(-1, "/new#123", "123"))).matches[0]!!
        match.id shouldBe 12
        match.read shouldBe true
        match.bookmark shouldBe true
        match.lastPageRead shouldBe 9
    }
    @Test fun `unique descriptive fingerprint matches but number alone does not`() {
        plan(listOf(row(1, "/old")), listOf(row(-1, "/new"))).matches.size shouldBe 1
        plan(listOf(row(1, "/old").copy(name = "Chapter 1")), listOf(row(-1, "/new").copy(name = "Chapter 1"))).matches.size shouldBe 0
    }
    @Test fun `ambiguous identities and conflicting IDs cannot auto merge`() {
        plan(listOf(row(1, "/a"), row(2, "/b")), listOf(row(-1, "/c"))).let {
            it.matches.size shouldBe 0; it.unresolved shouldBe true
        }
        plan(listOf(row(1, "/old", "1")), listOf(row(-1, "/new", "2"))).matches.size shouldBe 0
    }
    @Test fun `verified redirect matches but never another source or manga`() {
        ChapterIdentity.reconcile(4, 4, 10, listOf(row(1, "/old", "1")), listOf(row(-1, "/new", "2")), mapOf("/old" to "/new")).matches[0]?.id shouldBe 1
        assertThrows<IllegalArgumentException> { ChapterIdentity.reconcile(4, 5, 10, listOf(row(1, "/a")), emptyList()) }
        assertThrows<IllegalArgumentException> { plan(listOf(row(1, "/a").copy(mangaId = 11)), emptyList()) }
    }
    @Test fun `legacy Azora URL fragments are source scoped stable IDs`() {
        val old = row(7, "/series/a/chapter-1#89157")
        val moved = row(-1, "/series/a/changed#89157")
        ChapterIdentity.reconcile(2482399499047903203L, 2482399499047903203L, 10, listOf(old), listOf(moved)).matches[0]?.id shouldBe 7
        ChapterIdentity.remoteIds(old, 4).isEmpty() shouldBe true
    }
    @Test fun `redirect to a different numbered chapter is not equivalence`() {
        ChapterIdentity.reconcile(4, 4, 10, listOf(row(1, "/old", "1")), listOf(row(-1, "/new", "2").copy(chapterNumber = 2.0)), mapOf("/old" to "/new")).matches.size shouldBe 0
    }
    @Test fun `partial results cannot prove fingerprint uniqueness`() {
        val plan = ChapterIdentity.reconcile(4, 4, 10, listOf(row(1, "/old")), listOf(row(-1, "/new")), allowFingerprint = false)
        plan.matches.isEmpty() shouldBe true
        plan.blocked shouldBe setOf(0)
        plan.unresolved shouldBe true
    }
}
