package eu.kanade.tachiyomi.source.audit

import android.content.Context
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import eu.kanade.tachiyomi.source.model.ChapterFetchCompleteness
import eu.kanade.tachiyomi.source.repair.*
import java.io.File
import java.io.IOException
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Opt-in emulator acceptance: real public HTTPS, APK public key, production engine/client.
 * Separate storage/feed namespace; never changes registered sources, library, or production rules.
 */
object ProductionFeedAudit {
    suspend fun run(context: Context, phase: String, record: (String) -> Unit) {
        val client = Injekt.get<NetworkHelper>().client
        val source = object : HttpSource() {
            // Never registered: these production-signed fixtures cannot match a built-in ID.
            override val id = 9223372036854775708L
            override val name = "Unregistered production-feed diagnostic"
            override val lang = "ar"
            override val supportsLatest = true
            override val baseUrl = BuildConfig.SOURCE_RULES_URL
            override val client = client
            override fun headersBuilder() = Headers.Builder()
        }
        val id = source.id
        val base = BuildConfig.SOURCE_RULES_URL
        check(base == "https://mangaro-source-rules.vercel.app")
        val verifier = RuleVerifier(BuildConfig.SOURCE_RULES_PUBLIC_KEY)
        val store = RuleStore(File(context.filesDir, "production-feed-acceptance-diagnostic"), verifier)
        fun transport(path: String) = HttpsRuleTransport("$base/verification/$path", client)
        if (phase == "activate") {
            check(store.active(id) == null) { "Acceptance store already populated; use a fresh emulator acceptance directory" }
            val first = requireNotNull(transport("v1").fetch(id))
            check(verifier.verify(first).revision == 1L)
            check(runCatching { store.stage(2482399499047903203L, first) }.isFailure)
            record("Signed diagnostic envelope rejected for real Azora ID (cross-source replay protection)")
            store.stage(id, first)
            store.activate(id) // Explicitly seed old signed rules to simulate a formerly working representation.
            record("Production feed HTTPS fetch + P-256 signature verified revision=1")
            val engine = RuleRepairEngine(store, transport("v2"))
            engine.initialize(id)
            val interpreter = requireNotNull(engine.active(source))
            val manga = interpreter.catalogue("popular", 1).mangas.first()
            val chapter = interpreter.chapters(interpreter.details(manga)).first()
            var oldFailed = false
            try { interpreter.pages(chapter) } catch (e: IOException) { oldFailed = true }
            check(oldFailed) { "Old pages field did not fail" }
            val pages = RepairableSource(source, engine).getPageList(chapter)
            check(pages.size == 1 && store.active(id)?.revision == 2L)
            check(store.read(id).lastKnownGood?.let(verifier::verify)?.revision == 1L)
            val updated = RepairableSource(source, engine).getMangaUpdate(manga, emptyList(), true, true)
            check(updated.chapterCompleteness == ChapterFetchCompleteness.COMPLETE && updated.chapters.size == 1)
            record("Old pages -> images failure repaired automatically: signed revision=2 validated, activated, original operation replayed; chapters COMPLETE=1")
        } else {
            check(phase == "restart")
            check(store.active(id)?.revision == 2L) { "Signed activation did not survive process restart" }
            var offlineCalls = 0
            val offline = RuleTransport { offlineCalls++; throw java.net.UnknownHostException("Emulated feed outage") }
            val engine = RuleRepairEngine(store, offline, clock = { System.currentTimeMillis() + 120_000 })
            engine.initialize(id)
            check(!engine.check(source, force = true) && offlineCalls == 1)
            check(engine.active(source)?.rules?.revision == 2L)
            val repaired = RepairableSource(source, engine)
            val manga = repaired.getPopularManga(1).mangas.first()
            val chapter = repaired.getMangaUpdate(manga, emptyList(), true, true).chapters.first()
            check(repaired.getPageList(chapter).size == 1)
            record("Separate Android process restart restored signed revision=2; simulated feed offline retained ACTIVE and working HTTPS pages")
            val bad = requireNotNull(transport("bad-signature").fetch(id))
            check(runCatching { verifier.verify(bad) }.isFailure)
            check(!RuleRepairEngine(store, transport("bad-signature"), clock = { System.currentTimeMillis() + 240_000 }).check(source, force = true))
            check(store.active(id)?.revision == 2L)
            record("Live tampered envelope signature rejected; active revision=2 unchanged")
            check(!RuleRepairEngine(store, transport("v3"), clock = { System.currentTimeMillis() + 360_000 }).check(source, force = true))
            check(store.active(id)?.revision == 2L && store.read(id).candidate == null)
            record("Live correctly signed broken revision=3 rejected before activation; active and last-known-good preserved")
        }
    }
}
