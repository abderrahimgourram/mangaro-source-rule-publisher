package eu.kanade.tachiyomi.source.audit

import android.content.Context
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.model.ChapterFetchCompleteness
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.repair.*
import java.io.File
import java.io.IOException
import okhttp3.Headers
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Live publisher staging acceptance; no source registration or production-store mutations. */
object PublisherEngineAudit {
    suspend fun run(context: Context, phase: String, record: (String) -> Unit) {
        val feed = "https://mangaro-source-rules-staging.vercel.app"
        check(feed != BuildConfig.SOURCE_RULES_URL)
        val client = Injekt.get<NetworkHelper>().client
        val id = 9223372036854775708L
        val original = object : HttpSource() {
            override val id = 9223372036854775708L
            override val name = "Unregistered publisher diagnostic"
            override val lang = "ar"
            override val baseUrl = feed
            override val supportsLatest = true
            override val client = client
            override fun headersBuilder() = Headers.Builder()
        }
        val verifier = RuleVerifier(BuildConfig.SOURCE_RULES_PUBLIC_KEY)
        val prior = requireNotNull(HttpsRuleTransport("$feed/baseline", client).fetch(id))
        val old = verifier.verify(prior)
        val store = RuleStore(File(context.filesDir, "publisher-engine-acceptance-${old.revision}"), verifier)
        val engine = RuleRepairEngine(store, HttpsRuleTransport(feed, client))
        if (phase == "activate") {
            check(store.active(id) == null)
            store.stage(id, prior); store.activate(id) // Previous state proven healthy by publisher's HTTPS audit.
            engine.initialize(id)
            val stale = requireNotNull(engine.active(original))
            var failed = false
            try { stale.catalogue("popular", 1) } catch (e: IOException) { failed = true }
            check(failed) { "Old mapping did not reproduce changed representation" }
            val source = RepairableSource(original, engine)
            val produced = source.getPopularManga(1)
            check(produced.mangas.size == 2 && produced.hasNextPage)
            val update = source.getMangaUpdate(produced.mangas.first(), emptyList(), true, true)
            check(update.chapterCompleteness == ChapterFetchCompleteness.COMPLETE && update.chapters.size == 2)
            val pages = source.getPageList(update.chapters.first())
            check(pages.size == 2)
            check(store.active(id)!!.revision > old.revision)
            record("Publisher HTTPS candidate accepted by unchanged Android engine: catalogue -> same manga URL/memo -> details -> COMPLETE chapters=2 -> pages=2; revision=${store.active(id)!!.revision}")
        } else {
            check(phase == "restart")
            engine.initialize(id)
            check(store.active(id)!!.revision > old.revision)
            val source = RepairableSource(original, engine)
            val produced = source.getPopularManga(2)
            check(produced.mangas.size == 2)
            val update = source.getMangaUpdate(produced.mangas.first(), emptyList(), true, true)
            check(update.chapterCompleteness == ChapterFetchCompleteness.COMPLETE && update.chapters.size == 3)
            check(source.getPageList(update.chapters.first()).size == 2)
            record("Publisher Android process restart restored signed active rules; catalogue page2 -> same produced manga -> COMPLETE chapters=3 -> pages=2")
        }
    }
}
