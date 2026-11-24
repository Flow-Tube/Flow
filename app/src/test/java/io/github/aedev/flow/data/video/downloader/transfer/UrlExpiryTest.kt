package io.github.aedev.flow.data.video.downloader.transfer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UrlExpiryTest {
    private val now = 1_800_000_000L
    private val nanos = 5_000_000_000L

    private fun url(expire: Long) = "https://rr1---sn.googlevideo.com/videoplayback?expire=$expire&itag=137&clen=10"

    private fun deadline(url: String) = RangeDownloader.refreshDeadlineNanos(url, now, nanos)

    @Test
    fun `a URL is minted again five minutes before its deadline`() {
        val sixHours = 6 * 3_600L

        assertThat(deadline(url(now + sixHours))).isEqualTo(nanos + (sixHours - 300) * 1_000_000_000L)
    }

    @Test
    fun `a URL that already looks expired is left to the 403 path, as the clock may be off`() {
        assertThat(deadline(url(now + 299))).isNull()
        assertThat(deadline(url(now - 7 * 3_600))).isNull()
    }

    @Test
    fun `a URL without a deadline is never minted again early`() {
        assertThat(deadline("https://example.com/file.mp4")).isNull()
    }
}
