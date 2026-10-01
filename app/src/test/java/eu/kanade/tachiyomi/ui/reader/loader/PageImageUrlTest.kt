package eu.kanade.tachiyomi.ui.reader.loader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException

class PageImageUrlTest {
    @Test
    fun `http and https keep the network path`() {
        PageImageUrl.parse("http://example.com/1.jpg") shouldBe PageImageUrl.Http
        PageImageUrl.parse("https://example.com/1.jpg") shouldBe PageImageUrl.Http
    }

    @Test
    fun `base64 and percent encoded data images decode locally`() {
        val base64 = PageImageUrl.parse("data:image/gif;base64,R0lGODlh") as PageImageUrl.InlineImage
        String(base64.bytes) shouldBe "GIF89a"
        val percent = PageImageUrl.parse("data:image/gif,GIF89a%00") as PageImageUrl.InlineImage
        percent.bytes.size shouldBe 7
        percent.bytes.last() shouldBe 0.toByte()
    }

    @Test
    fun `malformed image data is rejected before HTTP`() {
        assertThrows<IOException> { PageImageUrl.parse("data:image/png;base64,%%") }
        assertThrows<IOException> { PageImageUrl.parse("data:image/png;base64") }
        assertThrows<IOException> { PageImageUrl.parse("data:text/plain,hello") }
    }

    @Test
    fun `unsupported scheme is rejected before HTTP`() {
        assertThrows<IOException> { PageImageUrl.parse("ftp://example.com/1.jpg") }
    }
}
