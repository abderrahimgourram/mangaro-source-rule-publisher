package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.jsoup.Jsoup
import eu.kanade.tachiyomi.source.internal.util.SourceValidationUtil
import java.io.IOException
import java.util.Base64

/** MangaDar now puts signed image URLs in data-mds; img src is a transparent placeholder. */
internal object MangaDarPageResolver {
    private const val SOURCE_ID = 3975276517041363504L

    fun supports(source: HttpSource): Boolean = source.id == SOURCE_ID && source !is eu.kanade.tachiyomi.source.repair.RepairableSource

    suspend fun getPages(source: HttpSource, chapter: SChapter): List<Page> {
        val request = Request.Builder()
            .url(source.getChapterUrl(chapter))
            .headers(source.headers)
            .build()
        val html = source.client.newCall(request).awaitSuccess().use { it.body.string() }
        return parseImageUrls(html).mapIndexed { index, url -> Page(index, imageUrl = url) }
    }

    fun parseImageUrls(html: String): List<String> {
        val document = Jsoup.parse(html)
        SourceValidationUtil.checkCloudflareOrError(document)
        val images = document.select(".reader-page img")
        if (images.isEmpty()) throw IOException("MangaDar chapter contains no reader images")
        return images.map { image ->
            val encoded = image.attr("data-mds")
            if (encoded.isBlank() || encoded.length > 4096) {
                throw IOException("MangaDar reader image is missing a signed URL")
            }
            val url = try {
                String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
            } catch (error: IllegalArgumentException) {
                throw IOException("MangaDar reader image has an invalid signed URL", error)
            }
            if (url.toHttpUrlOrNull() == null) {
                throw IOException("MangaDar reader image has a non-HTTP URL")
            }
            url
        }
    }
}
