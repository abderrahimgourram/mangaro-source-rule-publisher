package eu.kanade.tachiyomi.source.internal.mangastarz

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URI

object MangaStarzHtmlFetcher {

    fun isValidOrigin(urlStr: String?): Boolean {
        if (urlStr.isNullOrBlank()) return false
        val uri = try { URI(urlStr) } catch (_: Exception) { return false }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        return scheme == "https" && (host == "starzmanga.com" || host == "www.starzmanga.com")
    }

    suspend fun fetchDocument(context: Context, url: String, headers: Map<String, String>): Document {
        if (!isValidOrigin(url)) {
            throw IOException("Manga Starz invalid target URL origin: $url")
        }
        val (html, finalUrl) = fetchHtml(context, url, headers)
        return Jsoup.parse(html, finalUrl)
    }

    private suspend fun fetchHtml(context: Context, url: String, headers: Map<String, String>): Pair<String, String> = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<Pair<String, String>>()
        var webView: WebView? = null
        try {
            webView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                headers["User-Agent"]?.let { settings.userAgentString = it }
            }

            var finished = false

            fun checkHtml() {
                val currentView = webView ?: return
                if (finished) return
                val currentUrl = currentView.url.orEmpty()
                if (!isValidOrigin(currentUrl)) return

                currentView.evaluateJavascript("document.documentElement.outerHTML") { htmlJson ->
                    if (finished || htmlJson.isNullOrBlank() || htmlJson == "null" || htmlJson == "\"\"") return@evaluateJavascript
                    val rawHtml = try {
                        if (htmlJson.startsWith("\"") && htmlJson.endsWith("\"")) {
                            JSONObject("{ \"k\": $htmlJson }").getString("k")
                        } else {
                            htmlJson
                        }
                    } catch (_: Exception) {
                        htmlJson
                    }

                    if (rawHtml.contains("page-item-detail") ||
                        rawHtml.contains("post-title") ||
                        rawHtml.contains("wp-manga-chapter") ||
                        rawHtml.contains("reading-content")
                    ) {
                        finished = true
                        deferred.complete(Pair(rawHtml, currentUrl))
                    }
                }
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    CookieManager.getInstance().flush()
                    if (isValidOrigin(loadedUrl)) {
                        checkHtml()
                    }
                }

                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                    if (request?.isForMainFrame == true && errorResponse?.statusCode == 403) {
                        if (isValidOrigin(request.url?.toString())) {
                            checkHtml()
                        }
                    }
                }
            }

            webView.loadUrl(url, headers)

            val result = withTimeoutOrNull(20_000L) {
                while (!deferred.isCompleted) {
                    checkHtml()
                    delay(500)
                }
                deferred.await()
            }

            try {
                webView.stopLoading()
                webView.destroy()
            } catch (_: Exception) {}

            if (result == null) {
                throw IOException("Cloudflare challenge or protection page detected")
            }

            result
        } catch (e: Exception) {
            try {
                webView?.stopLoading()
                webView?.destroy()
            } catch (_: Exception) {}
            if (e is IOException) throw e else throw IOException(e.message, e)
        }
    }
}
