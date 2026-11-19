package io.github.aedev.flow.data.shorts

import androidx.media3.common.util.UnstableApi
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.player.stream.PlaybackTestStreams.h264Format
import io.github.aedev.flow.player.stream.PlaybackTestStreams.vp9Format
import org.junit.Test

@UnstableApi
class ShortsAutoLadderTest {
    private val formats =
        listOf(h264Format(360, 700_000), h264Format(720, 2_500_000), h264Format(1080, 4_500_000), vp9Format(720, 2_000_000))

    @Test
    fun `Auto plays every rung of the chosen codec as one adaptive source`() {
        val manifest = shortsAutoLadder(formats, formats[2], durationMs = 30_000L)!!

        val heights =
            manifest
                .getPeriod(0)
                .adaptationSets
                .single()
                .representations
                .map { it.format.height }
        assertThat(heights).containsExactly(360, 720, 1080)
    }

    @Test
    fun `no duration or a single rung leaves Auto on one stream`() {
        assertThat(shortsAutoLadder(formats, formats[2], durationMs = null)).isNull()
        assertThat(shortsAutoLadder(listOf(formats[3]), formats[3], durationMs = 30_000L)).isNull()
    }
}
