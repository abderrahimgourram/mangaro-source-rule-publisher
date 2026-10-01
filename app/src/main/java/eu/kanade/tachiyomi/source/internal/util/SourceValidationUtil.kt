package eu.kanade.tachiyomi.source.internal.util

import org.jsoup.nodes.Document
import okhttp3.Response
import org.jsoup.Jsoup
import java.io.IOException

object SourceValidationUtil {

    fun checkCloudflareOrError(document: Document) {
        val title = document.title().lowercase()
        val text = document.text().lowercase()

        if (document.body().children().isEmpty() && document.body().text().isBlank()) {
            throw IOException("Source returned an empty HTML document")
        }
        if (title.contains("404") || title.contains("not found") || title.contains("service unavailable")) {
            throw IOException("Source returned an error page")
        }
        if (title.contains("just a moment") ||
            title.contains("attention required") ||
            title.contains("access denied") ||
            text.contains("cf-browser-verification") ||
            document.select("div.challenge-running, #challenge-form, #cf-wrapper, script[src*=/cdn-cgi/challenge-platform/]").isNotEmpty()
        ) {
            throw IOException("Cloudflare challenge or protection page detected")
        }
    }
    /** No body content, cookies, authorization headers or query values in diagnostics. */
    fun parseCatalogueResponse(response: Response, parser: (Document) -> eu.kanade.tachiyomi.source.model.MangasPage): eu.kanade.tachiyomi.source.model.MangasPage = response.use {
        val body = it.body.string()
        val document = Jsoup.parse(body, it.request.url.toString())
        try {
            val contentType = it.header("Content-Type").orEmpty()
            if (contentType.isNotEmpty() && !contentType.contains("html", true)) throw IOException("Unexpected catalogue content type")
            checkCloudflareOrError(document)
            parser(document)
        } catch (error: IOException) {
            val routes = document.select("a[href]").mapNotNull { anchor ->
                runCatching { java.net.URI(anchor.attr("href")).path }.getOrNull()
            }.map { path ->
                path.trim('/').split('/').take(3).mapIndexed { index, part ->
                    if (index == 0 || part == "page") part else "*"
                }.joinToString("/")
            }.distinct().take(12)
            throw IOException(
                "${error.message}; status=${it.code}, final=${it.request.url.scheme}://${it.request.url.host}${it.request.url.encodedPath}, " +
                    "type=${it.header("Content-Type")}, bytes=${body.toByteArray().size}, networkStatus=${it.networkResponse?.code}, " +
                    "vary=${it.header("Vary")}, encoding=${it.networkResponse?.header("Content-Encoding")}, cache=${it.cacheResponse != null}, " +
                    "age=${it.header("Age")}, server=${it.header("Server")}, " +
                    "anchors=${document.select("a[href]").size}, templates=${document.select("template").size}, " +
                    "scripts=${document.select("script").size}, main=${document.select("main").size}, " +
                    "mobileUA=${it.request.header("User-Agent")?.contains("Mobile") == true}, routes=$routes",
                error,
            )
        }
    }

}
