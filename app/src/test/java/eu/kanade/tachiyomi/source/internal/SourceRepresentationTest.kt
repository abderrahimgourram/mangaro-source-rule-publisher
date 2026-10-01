package eu.kanade.tachiyomi.source.internal

import eu.kanade.tachiyomi.source.internal.util.SourceValidationUtil
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

class SourceRepresentationTest {
    @Test
    fun `HTTP 200 JSON and empty HTML cannot become successful empty catalogues`() {
        for ((type, body) in listOf("application/json" to "{}", "text/html" to "")) {
            val response = okhttp3.Response.Builder()
                .request(okhttp3.Request.Builder().url("https://mangadar.com/manga/").build())
                .protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", type)
                .body(body.toResponseBody()).build()
            assertThrows<IOException> {
                SourceValidationUtil.parseCatalogueResponse(response) { eu.kanade.tachiyomi.source.model.MangasPage(emptyList(), false) }
            }
        }
    }

}
