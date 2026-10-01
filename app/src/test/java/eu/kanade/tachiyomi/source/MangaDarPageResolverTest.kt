package eu.kanade.tachiyomi.source

import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException

class MangaDarPageResolverTest {
    @Test
    fun `MangaDar placeholder is replaced by signed page URLs in order`() {
        val html = """
            <div class="reader-page"><img src="data:image/gif;base64,R0lGODlhAQABAAAAACH5BAEKAAEALAAAAAABAAEAAAICTAEAOw=="
                data-mds="aHR0cHM6Ly9tYW5nYWRhci5jb20vP21kcnM9MSZpPTA=" /></div>
            <div class="reader-page"><img src="data:image/gif;base64,R0lGODlhAQABAAAAACH5BAEKAAEALAAAAAABAAEAAAICTAEAOw=="
                data-mds="aHR0cHM6Ly9tYW5nYWRhci5jb20vP21kcnM9MSZpPTE=" /></div>
        """.trimIndent()
        MangaDarPageResolver.parseImageUrls(html).shouldContainExactly(
            "https://mangadar.com/?mdrs=1&i=0",
            "https://mangadar.com/?mdrs=1&i=1",
        )
    }

    @Test
    fun `missing or invalid signed URL does not pass placeholder to HTTP`() {
        assertThrows<IOException> {
            MangaDarPageResolver.parseImageUrls("<div class='reader-page'><img src='data:image/gif;base64,AA=='></div>")
        }
        assertThrows<IOException> {
            MangaDarPageResolver.parseImageUrls("<div class='reader-page'><img data-mds='ZGF0YTppbWFnZS9wbmc7YmFzZTY0LEFBPT0='></div>")
        }
    }
}
