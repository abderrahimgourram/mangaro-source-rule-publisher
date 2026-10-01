package eu.kanade.tachiyomi.ui.reader.loader

import java.io.IOException
import java.util.Base64

internal sealed interface PageImageUrl {
    data object Http : PageImageUrl
    class InlineImage(val bytes: ByteArray) : PageImageUrl

    companion object {
        private const val MAX_IMAGE_BYTES = 16 * 1024 * 1024

        fun parse(url: String): PageImageUrl {
            if (url.startsWith("http://", true) || url.startsWith("https://", true)) return Http
            if (!url.startsWith("data:", true)) throw IOException("Unsupported reader image URL scheme")

            val comma = url.indexOf(',')
            if (comma < 0) throw IOException("Malformed data image URI")
            val metadata = url.substring(5, comma)
            if (!metadata.substringBefore(';').startsWith("image/", true)) {
                throw IOException("Data URI is not an image")
            }
            val payload = url.substring(comma + 1)
            if (payload.length > MAX_IMAGE_BYTES * 4 / 3 + 4) {
                throw IOException("Data image exceeds size limit")
            }
            val bytes = try {
                if (metadata.split(';').any { it.equals("base64", true) }) {
                    Base64.getDecoder().decode(payload)
                } else {
                    decodePercentEncoded(payload)
                }
            } catch (error: IllegalArgumentException) {
                throw IOException("Malformed data image URI", error)
            }
            if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) {
                throw IOException("Data image is empty or exceeds size limit")
            }
            return InlineImage(bytes)
        }

        private fun decodePercentEncoded(payload: String): ByteArray {
            val result = ByteArray(payload.length)
            var output = 0
            var index = 0
            while (index < payload.length) {
                val char = payload[index]
                if (char == '%') {
                    if (index + 2 >= payload.length) throw IllegalArgumentException("Incomplete percent escape")
                    val hex = payload.substring(index + 1, index + 3).toIntOrNull(16)
                        ?: throw IllegalArgumentException("Invalid percent escape")
                    result[output++] = hex.toByte()
                    index += 3
                } else {
                    if (char.code > 127) throw IllegalArgumentException("Non-ASCII data URI")
                    result[output++] = char.code.toByte()
                    index++
                }
            }
            return result.copyOf(output)
        }
    }
}
