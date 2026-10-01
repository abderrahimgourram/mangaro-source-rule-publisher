package eu.kanade.tachiyomi.source.repair

import eu.kanade.tachiyomi.source.model.*
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Existing collision policy still chooses external/internal by the identical source ID. */
class RepairableSource(val original: HttpSource, private val engine: RuleRepairEngine) : HttpSource() {
    override val id get() = original.id
    override val name get() = original.name
    override val lang get() = original.lang
    override val versionId get() = original.versionId
    override val supportsLatest get() = original.supportsLatest
    override val baseUrl get() = engine.active(original)?.rules?.baseUrl ?: original.baseUrl
    private val dynamicClient by lazy {
        original.client.newBuilder().addInterceptor { chain ->
            val request = chain.request().newBuilder()
            engine.active(original)?.rules?.headers?.forEach { (k, v) -> if ('{' !in v) request.header(k, v) }
            chain.proceed(request.build())
        }.build()
    }
    override val client get() = dynamicClient
    override fun toString() = original.toString()
    override fun headersBuilder(): Headers.Builder = original.headers.newBuilder().apply {
        engine.active(original)?.rules?.headers?.forEach { (k, v) -> if ('{' !in v) set(k, v) }
    }
    override fun getFilterList() = if (engine.hasActive(id)) FilterList() else original.getFilterList()
    private val catalogueWindow = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Set<String>>>()
    private fun catalogueResult(key: String, page: Int, result: MangasPage): MangasPage {
        val identities = result.mangas.map { it.url }.toSet()
        val previous = catalogueWindow[key]
        if (identities.isNotEmpty() && previous?.first == page - 1 && previous.second == identities) throw java.io.IOException("Repeated catalogue page")
        if (!key.startsWith("search:") && page == 1 && result.mangas.isEmpty()) throw java.io.IOException("Empty populated catalogue")
        if (catalogueWindow.size >= 16) catalogueWindow.keys.firstOrNull()?.let(catalogueWindow::remove)
        catalogueWindow[key] = page to identities
        return result
    }
    override suspend fun getPopularManga(page: Int) = engine.execute(original,
        { catalogueResult("popular", page, original.getPopularManga(page)) }) { catalogueResult("popular", page, it.catalogue("popular", page)) }
    override suspend fun getLatestUpdates(page: Int) = engine.execute(original,
        { catalogueResult("latest", page, original.getLatestUpdates(page)) }) { catalogueResult("latest", page, it.catalogue("latest", page)) }
    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList) = engine.execute(original,
        { catalogueResult("search:$query", page, original.getSearchManga(page, query, filters)) }) { catalogueResult("search:$query", page, it.catalogue("search", page, query)) }
    override suspend fun getMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean): SMangaUpdate {
        // Existing local chapter identities are an additional pre-activation floor; no count-only match.
        engine.rememberExisting(id, manga, chapters)
        return engine.execute(original, {
            original.getMangaUpdate(manga, chapters, fetchDetails, fetchChapters).also {
                if (fetchChapters && it.chapterCompleteness in setOf(ChapterFetchCompleteness.FAILED, ChapterFetchCompleteness.PARTIAL)) throw java.io.IOException("Failed chapter representation")
            }
        }) { interpreter ->
            val details = if (fetchDetails || fetchChapters) interpreter.details(manga) else manga
            val result = if (fetchChapters) interpreter.chapters(details) else chapters
            if (fetchChapters) engine.verifyExisting(id, manga.url, result)
            SMangaUpdate(details, result, if (fetchChapters) ChapterFetchCompleteness.COMPLETE else ChapterFetchCompleteness.DEGRADED)
        }.also { if (fetchChapters && it.chapterCompleteness == ChapterFetchCompleteness.COMPLETE) engine.remember(id, manga, it.chapters) }
    }
    override suspend fun getPageList(chapter: SChapter) = engine.execute(original, { original.getPageList(chapter).also { if (it.isEmpty()) throw java.io.IOException("Missing reader image field") } }) { it.pages(chapter) }
    override suspend fun getImageUrl(page: Page) = original.getImageUrl(page)
    override fun getMangaUrl(manga: SManga) = if (engine.hasActive(id)) absolute(manga.url) else original.getMangaUrl(manga)
    override fun getChapterUrl(chapter: SChapter) = if (engine.hasActive(id)) absolute(chapter.url) else original.getChapterUrl(chapter)
    private fun absolute(url: String) = baseUrl.toHttpUrl().resolve(url)?.toString() ?: error("Invalid source URL")
    override fun imageRequest(page: Page): Request = if (engine.hasActive(id)) Request.Builder().url(requireNotNull(page.imageUrl)).apply {
        engine.active(original)?.rules?.headers?.forEach { (k, v) -> if ('{' !in v) header(k, v) }
    }.build() else originalImageRequest(page)
    // Native imageRequest can include source-specific tokens. Preserve it without reflection.
    private fun originalImageRequest(page: Page): Request = original.repairImageRequest(page)
}
