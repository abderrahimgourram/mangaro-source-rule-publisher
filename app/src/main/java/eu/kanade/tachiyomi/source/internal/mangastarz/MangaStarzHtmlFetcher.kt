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

object MangaStarzHtmlFetcher {

    suspend fun fetchDocument(context: Context, url: String, headers: Map<String, String>): Document {
        val html = fetchHtml(context, url, headers)
        return Jsoup.parse(html, url)
    }

    private suspend fun fetchHtml(context: Context, url: String, headers: Map<String, String>): String = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<String>()
        var webView: WebView? = null
        try {
            webView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                headers["User-Agent"]?.let { settings.userAgentString = it }
            }

            var finished = false

            fun checkHtml() {
                if (finished || webView == null) return
                webView?.evaluateJavascript("document.documentElement.outerHTML") { htmlJson ->
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
                        rawHtml.contains("reading-content") ||
                        rawHtml.contains("item-thumb") ||
                        rawHtml.contains("site-content")
                    ) {
                        finished = true
                        deferred.complete(rawHtml)
                    }
                }
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    CookieManager.getInstance().flush()
                    checkHtml()
                }

                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                    if (request?.isForMainFrame == true && errorResponse?.statusCode == 403) {
                        checkHtml()
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
