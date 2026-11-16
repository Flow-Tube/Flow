package io.github.aedev.flow.player.datasource

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class UnreachableHostRetryDataSourceTest {
    private val streamUrl = "https://rr3---sn-gwpa-itqed.googlevideo.com/videoplayback?id=o-A"
    private val uri = mockk<Uri> { every { this@mockk.toString() } returns streamUrl }
    private val spec =
        DataSpec
            .Builder()
            .setUri(uri)
            .setKey("song")
            .build()
    private val unreachable = mutableListOf<String>()
    private val upstream = mockk<DataSource>(relaxed = true)
    private val source =
        UnreachableHostRetryDataSource(upstream) { _, url ->
            unreachable += url
            true
        }

    private fun openFailure(cause: IOException) =
        HttpDataSource.HttpDataSourceException(
            IOException(cause),
            spec,
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            HttpDataSource.HttpDataSourceException.TYPE_OPEN,
        )

    @Test
    fun `a server that never answers is reported and the song opens again`() {
        every { upstream.open(spec) } throws openFailure(SocketTimeoutException("failed to connect")) andThen 2_048L

        assertThat(source.open(spec)).isEqualTo(2_048L)
        assertThat(unreachable).containsExactly(streamUrl)
        verify(exactly = 1) { upstream.close() }
    }

    @Test
    fun `a second failure is the player's to handle`() {
        every { upstream.open(spec) } throws openFailure(SocketTimeoutException("failed to connect"))

        assertThrows(HttpDataSource.HttpDataSourceException::class.java) { source.open(spec) }
        assertThat(unreachable).hasSize(1)
    }

    @Test
    fun `no host name means no network, which is not retried here`() {
        every { upstream.open(spec) } throws openFailure(UnknownHostException("rr3---sn-gwpa-itqed.googlevideo.com"))

        assertThrows(HttpDataSource.HttpDataSourceException::class.java) { source.open(spec) }
        assertThat(unreachable).isEmpty()
    }

    @Test
    fun `a refusal is left to the refusal retry`() {
        val refused = HttpDataSource.InvalidResponseCodeException(403, null, null, emptyMap(), spec, ByteArray(0))
        every { upstream.open(spec) } throws refused

        assertThrows(HttpDataSource.InvalidResponseCodeException::class.java) { source.open(spec) }
        assertThat(unreachable).isEmpty()
    }
}
