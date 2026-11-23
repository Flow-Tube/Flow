package io.github.aedev.flow.data.video.downloader.resolve

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.innertube.models.response.PlayerResponse.StreamingData.Format
import io.github.aedev.flow.player.stream.InnerTubeVideoStreamExtractor.VideoExtractionResult
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

class RecentExtractionsTest {
    private var now = 1_800_000_000_000L
    private val recent = RecentExtractions { now }

    private fun result(expiresInSeconds: Long): VideoExtractionResult {
        val expire = now / 1000 + expiresInSeconds
        val format = mockk<Format> { every { url } returns "https://rr1.googlevideo.com/videoplayback?expire=$expire&itag=137" }
        return mockk(relaxed = true) {
            every { videoFormats } returns listOf(format)
            every { audioFormats } returns emptyList()
        }
    }

    @Test
    fun `a sheet's formats are used once by the download it starts`() {
        val loaded = result(expiresInSeconds = 6 * 3_600)
        recent.remember("v", loaded)

        assertThat(recent.take("v")).isSameInstanceAs(loaded)
        assertThat(recent.take("v")).isNull()
    }

    @Test
    fun `URLs close to expiry are extracted again`() {
        recent.remember("v", result(expiresInSeconds = 9 * 60))

        assertThat(recent.take("v")).isNull()
    }

    @Test
    fun `a result older than an hour is extracted again`() {
        recent.remember("v", result(expiresInSeconds = 6 * 3_600))
        now += RecentExtractions.MAX_AGE_MS + 1

        assertThat(recent.take("v")).isNull()
    }

    @Test
    fun `only the latest few sheets are kept`() {
        repeat(RecentExtractions.MAX_ENTRIES + 1) { recent.remember("v$it", result(expiresInSeconds = 6 * 3_600)) }

        assertThat(recent.take("v0")).isNull()
        assertThat(recent.take("v${RecentExtractions.MAX_ENTRIES}")).isNotNull()
    }
}
