package io.github.aedev.flow.player.live

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertThrows
import org.junit.Test

class LiveSegmentDataSourceTest {
    private val head = LiveHead(sequence = 100, mediaTimeMs = 500_000, serverTimeMs = 1_000_000)

    private class FakeHttp(
        private val code: Int,
        private val length: Long,
        private val headers: Map<String, List<String>>,
    ) : HttpDataSource {
        var opened: DataSpec? = null
        var closed = false

        override fun open(dataSpec: DataSpec): Long {
            opened = dataSpec
            return length
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ) = -1

        override fun getUri(): Uri? = opened?.uri

        override fun close() {
            closed = true
        }

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun setRequestProperty(
            name: String,
            value: String,
        ) = Unit

        override fun clearRequestProperty(name: String) = Unit

        override fun clearAllRequestProperties() = Unit

        override fun getResponseCode() = code

        override fun getResponseHeaders() = headers
    }

    private fun session(videoId: String) =
        LiveSegmentSession(
            videoId = videoId,
            formats = listOf(LiveSegmentFormat(140, "audio/mp4", "mp4a.40.2", 144_000)),
            urls = mapOf(140 to "https://r1.googlevideo.com/videoplayback?itag=140&expire=1"),
            clock = LiveSegmentClock(head, 5_000, 43_200_000),
            readaheadMs = 12_000,
            serverClockOffsetMs = 0,
            deviceNow = { 1_000_000 },
        ).also(LiveSegmentSessions::register)

    private fun uri(value: String): Uri =
        mockk {
            every { scheme } returns value.substringBefore("://")
            every { pathSegments } returns value.substringAfter("://").split('/').drop(1)
            every { this@mockk.toString() } returns value
        }

    private val newerHeadHeaders =
        mapOf(
            "x-head-seqnum" to listOf("101"),
            "x-head-time-millis" to listOf("505000"),
            "x-walltime-ms" to listOf("1005000"),
        )

    @Test
    fun `a segment number becomes the itag's URL with sq, and its headers feed the clock`() {
        val live = session("seg1")
        val http = FakeHttp(200, 80_000, newerHeadHeaders)
        val spec = DataSpec(uri("flowlive://segment/seg1/140/95"))

        val length = LiveSegmentDataSource(http, ::uri).open(spec)

        assertThat(length).isEqualTo(80_000)
        assertThat(http.opened!!.uri.toString()).isEqualTo("https://r1.googlevideo.com/videoplayback?itag=140&expire=1&sq=95")
        assertThat(live.clock.head.sequence).isEqualTo(101)
    }

    @Test
    fun `a 204 past the head is not ready, not an empty segment`() {
        session("seg2")
        val http = FakeHttp(204, 0, newerHeadHeaders)

        val error =
            assertThrows(LiveSegmentNotReadyException::class.java) {
                LiveSegmentDataSource(http, ::uri).open(DataSpec(uri("flowlive://segment/seg2/140/102")))
            }

        assertThat(error.sequence).isEqualTo(102)
        assertThat(http.closed).isTrue()
    }

    @Test
    fun `headers without a head leave the clock alone`() {
        assertThat(LiveHeadHeaders.parse(mapOf("x-head-seqnum" to listOf("5")))).isNull()
        assertThat(LiveHeadHeaders.parse(newerHeadHeaders.mapKeys { it.key.uppercase() }))
            .isEqualTo(LiveHead(sequence = 101, mediaTimeMs = 505_000, serverTimeMs = 1_005_000))
    }
}
