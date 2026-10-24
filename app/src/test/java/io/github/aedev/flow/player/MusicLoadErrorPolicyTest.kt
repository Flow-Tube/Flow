package io.github.aedev.flow.player

import androidx.media3.common.C
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.player.stream.PlaybackBlock
import io.github.aedev.flow.player.stream.PlaybackBlockedException
import io.github.aedev.flow.player.stream.VideoUnavailableException
import io.github.aedev.flow.utils.MusicResolveTimeoutException
import org.junit.Test
import java.io.IOException
import java.net.SocketException
import java.net.UnknownHostException

class MusicLoadErrorPolicyTest {
    @Test
    fun `a lost connection is waited out, a little longer each time, then given up`() {
        assertThat(MusicLoadErrorPolicy.retryDelayMs(errorCount = 1, connectionLost = true)).isEqualTo(1_000L)
        assertThat(MusicLoadErrorPolicy.retryDelayMs(errorCount = 9, connectionLost = true)).isEqualTo(5_000L)
        assertThat(MusicLoadErrorPolicy.retryDelayMs(errorCount = MusicLoadErrorPolicy.MAX_CONNECTION_RETRIES, connectionLost = true))
            .isEqualTo(5_000L)
        assertThat(MusicLoadErrorPolicy.retryDelayMs(errorCount = MusicLoadErrorPolicy.MAX_CONNECTION_RETRIES + 1, connectionLost = true))
            .isEqualTo(C.TIME_UNSET)
    }

    @Test
    fun `any other error gives up after Media3's usual three retries`() {
        assertThat(MusicLoadErrorPolicy.retryDelayMs(errorCount = 2, connectionLost = false)).isNull()
        assertThat(MusicLoadErrorPolicy.retryDelayMs(errorCount = 4, connectionLost = false)).isEqualTo(C.TIME_UNSET)
    }

    @Test
    fun `a lost connection is recognised however deep the platform wrapped it`() {
        assertThat(MusicLoadErrorPolicy.isConnectionLoss(IOException(IOException(UnknownHostException("www.youtube.com"))))).isTrue()
        assertThat(MusicLoadErrorPolicy.isConnectionLoss(IOException(SocketException("Software caused connection abort")))).isTrue()
        assertThat(MusicLoadErrorPolicy.isConnectionLoss(IOException("Response code: 403"))).isFalse()
    }

    @Test
    fun `offline, a streamed song's read error waits for the network`() {
        assertThat(MusicLoadErrorPolicy.countsAsLostConnection(IOException("Malformed URL"), "https") { true }).isTrue()
        assertThat(MusicLoadErrorPolicy.countsAsLostConnection(IOException("Malformed URL"), "music") { true }).isTrue()
        assertThat(MusicLoadErrorPolicy.countsAsLostConnection(IOException("Response code: 403"), "https") { false }).isFalse()
    }

    @Test
    fun `offline, a file on the device that cannot be read is not waited for`() {
        assertThat(MusicLoadErrorPolicy.countsAsLostConnection(IOException("EACCES"), "content") { true }).isFalse()
        assertThat(MusicLoadErrorPolicy.countsAsLostConnection(IOException("ENOENT"), "file") { true }).isFalse()
    }

    @Test
    fun `a song gone from every client or a resolve out of time is not retried by the loader`() {
        assertThat(MusicLoadErrorPolicy.isUnretryableResolve(IOException(VideoUnavailableException("abc")))).isTrue()
        assertThat(MusicLoadErrorPolicy.isUnretryableResolve(MusicResolveTimeoutException("abc", 30_000L))).isTrue()
        assertThat(MusicLoadErrorPolicy.isUnretryableResolve(IOException("Failed to resolve stream"))).isFalse()
        assertThat(MusicLoadErrorPolicy.isUnretryableResolve(IOException(UnknownHostException("www.youtube.com")))).isFalse()
    }

    @Test
    fun `only a gone song counts as unavailable, a timeout may still play`() {
        assertThat(MusicLoadErrorPolicy.isUnavailable(IOException(VideoUnavailableException("abc")))).isTrue()
        assertThat(MusicLoadErrorPolicy.isUnavailable(MusicResolveTimeoutException("abc", 30_000L))).isFalse()
    }

    @Test
    fun `a song refused to this viewer is not retried, a wall still is`() {
        val region = IOException("refused", PlaybackBlockedException(PlaybackBlock.REGION))
        val wall = IOException("refused", PlaybackBlockedException(PlaybackBlock.BOT_WALL))

        assertThat(MusicLoadErrorPolicy.viewerBlockOf(region)).isEqualTo(PlaybackBlock.REGION)
        assertThat(MusicLoadErrorPolicy.isUnretryableResolve(region)).isTrue()
        assertThat(MusicLoadErrorPolicy.viewerBlockOf(wall)).isNull()
        assertThat(MusicLoadErrorPolicy.isUnretryableResolve(wall)).isFalse()
        assertThat(MusicLoadErrorPolicy.isUnavailable(region)).isFalse()
    }
}
