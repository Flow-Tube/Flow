package io.github.aedev.flow.player.analytics

import androidx.media3.common.Format
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** #1295: a 360 video is told apart by the projection its container carries. */
class SphericalVideoListenerTest {
    @Test
    fun `a format with projection data is a 360 video`() {
        val format = Format.Builder().setProjectionData(byteArrayOf(1, 2, 3)).build()

        assertThat(format.isSpherical()).isTrue()
    }

    @Test
    fun `a flat format is not`() {
        val format =
            Format
                .Builder()
                .setWidth(1920)
                .setHeight(1080)
                .build()

        assertThat(format.isSpherical()).isFalse()
    }

    @Test
    fun `each new video starts flat until its format says otherwise`() {
        val reported = mutableListOf<Boolean>()
        val listener = SphericalVideoListener { reported += it }

        listener.onMediaItemTransition(EVENT_TIME, null, 0)

        assertThat(reported).containsExactly(false)
    }

    private companion object {
        val EVENT_TIME =
            AnalyticsListener.EventTime(
                0L,
                Timeline.EMPTY,
                0,
                null,
                0L,
                Timeline.EMPTY,
                0,
                null,
                0L,
                0L,
            )
    }
}
