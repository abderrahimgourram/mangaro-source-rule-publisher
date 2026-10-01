package eu.kanade.domain.chapter.interactor

import com.sun.net.httpserver.HttpServer
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import java.net.InetSocketAddress

class ResolveChapterRedirectsTest {
    @Test fun `actual HTTP redirect is verified against produced target and a valid page list`() = runBlocking<Unit> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/old") { exchange -> exchange.responseHeaders.add("Location", "/new"); exchange.sendResponseHeaders(302, -1); exchange.close() }
        server.createContext("/new") { exchange -> exchange.sendResponseHeaders(200, 0); exchange.responseBody.use { it.write("chapter".toByteArray()) } }
        server.createContext("/unchanged") { exchange -> exchange.sendResponseHeaders(200, 0); exchange.responseBody.use { it.write("chapter".toByteArray()) } }
        server.start()
        try {
            val source = mockk<HttpSource>()
            every { source.baseUrl } returns "http://127.0.0.1:${server.address.port}"
            every { source.headers } returns Headers.Builder().build()
            every { source.client } returns OkHttpClient()
            coEvery { source.getPageList(any()) } returns listOf(Page(0, "", "https://image/1.jpg"))
            val old = Chapter.create().copy(id = 7, mangaId = 10, url = "/old")
            val new = old.copy(id = -1, url = "/new")
            ResolveChapterRedirects().await(source, listOf(old), listOf(new)) shouldBe mapOf("/old" to "/new")
            ResolveChapterRedirects().await(source, listOf(old.copy(url = "/unchanged")), listOf(new)) shouldBe emptyMap()
            coEvery { source.getPageList(any()) } returns emptyList()
            ResolveChapterRedirects().await(source, listOf(old), listOf(new)) shouldBe emptyMap()
        } finally { server.stop(0) }
    }
}
