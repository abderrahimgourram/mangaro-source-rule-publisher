package eu.kanade.domain.chapter.interactor

import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.domain.chapter.model.Chapter

/** At most three unresolved URLs, five seconds each. A redirect alone is insufficient: validate pages. */
class ResolveChapterRedirects {
    suspend fun await(source: HttpSource, existing: List<Chapter>, incoming: List<Chapter>): Map<String, String> {
        fun absolute(url: String) = url.toHttpUrlOrNull() ?: source.baseUrl.toHttpUrlOrNull()?.resolve(url)
        val targets = incoming.groupBy { absolute(it.url) }
        val redirects = mutableMapOf<String, String>()
        for (old in existing.take(3)) {
            try {
                withTimeoutOrNull(5_000) {
                    val original = absolute(old.url) ?: return@withTimeoutOrNull
                    val final = source.client.newCall(GET(original.toString(), source.headers)).awaitSuccess().use {
                        it.request.url.takeIf { _ -> it.priorResponse?.code?.let { code -> code in 300..399 } == true }
                    }
                    if (final == null || final == original) return@withTimeoutOrNull
                    val target = targets[final]?.singleOrNull() ?: return@withTimeoutOrNull
                    if (source.getPageList(target.toSChapter()).isNotEmpty()) redirects[old.url] = target.url
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Unproven equivalence remains unresolved. */ }
        }
        return redirects
    }
}
