package io.github.aedev.flow.player.dlna

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayOutputStream

class LocalCastSourceTest {
    private val file = ByteArray(1000) { (it % 251).toByte() }

    private fun serve(
        range: String?,
        head: Boolean = false,
    ): Pair<String, ByteArray> {
        val out = ByteArrayOutputStream()
        LocalCastSource.respond(out, "audio/mp4", file.size.toLong(), range, head) { file.inputStream() }
        val bytes = out.toByteArray()
        val split = String(bytes, Charsets.ISO_8859_1).indexOf("\r\n\r\n") + 4
        return String(bytes, 0, split, Charsets.ISO_8859_1) to bytes.copyOfRange(split, bytes.size)
    }

    @Test
    fun `no range serves the whole file`() {
        val (headers, body) = serve(null)

        assertThat(headers).startsWith("HTTP/1.1 200 OK")
        assertThat(headers).contains("Content-Length: 1000\r\n")
        assertThat(body).isEqualTo(file)
    }

    @Test
    fun `a range serves those bytes as partial content`() {
        val (headers, body) = serve("bytes=100-199")

        assertThat(headers).startsWith("HTTP/1.1 206 Partial Content")
        assertThat(headers).contains("Content-Range: bytes 100-199/1000\r\n")
        assertThat(body).isEqualTo(file.copyOfRange(100, 200))
    }

    @Test
    fun `an open range runs to the end`() {
        val (headers, body) = serve("bytes=900-")

        assertThat(headers).contains("Content-Range: bytes 900-999/1000\r\n")
        assertThat(body).isEqualTo(file.copyOfRange(900, 1000))
    }

    @Test
    fun `head sends the headers only`() {
        val (headers, body) = serve(null, head = true)

        assertThat(headers).contains("Accept-Ranges: bytes\r\n")
        assertThat(body).isEmpty()
    }

    @Test
    fun `a range past the end is refused`() {
        val (headers, _) = serve("bytes=1000-")

        assertThat(headers).startsWith("HTTP/1.1 416")
    }

    @Test
    fun `ranges are read as renderers send them`() {
        assertThat(LocalCastSource.byteRange(null, 10)).isEqualTo(0L..9L)
        assertThat(LocalCastSource.byteRange("bytes=2-4", 10)).isEqualTo(2L..4L)
        assertThat(LocalCastSource.byteRange("bytes=2-40", 10)).isEqualTo(2L..9L)
        assertThat(LocalCastSource.byteRange("bytes=-3", 10)).isEqualTo(7L..9L)
        assertThat(LocalCastSource.byteRange("bytes=0-1, 5-6", 10)).isEqualTo(0L..1L)
        assertThat(LocalCastSource.byteRange("bytes=5-2", 10)).isNull()
    }
}
