package io.github.aedev.flow.player.shorts

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShortsSlotsTest {
    private val slots = ShortsSlots(size = 3)

    @Test
    fun `a slot is owned only by the index and id it was assigned`() {
        slots.assign(1, index = 4, videoId = "a", ShortsSlotStreams(videoUrl = "v", audioUrl = "x", audioManifest = "m"))

        assertThat(slots.owns(1, index = 4, videoId = "a")).isTrue()
        assertThat(slots.owns(1, index = 7, videoId = "a")).isFalse()
        assertThat(slots.owns(1, index = 4, videoId = "b")).isFalse()
        assertThat(slots.streams[1]?.audioManifest).isEqualTo("m")
    }

    @Test
    fun `clearing a slot forgets everything it held`() {
        val streams = ShortsSlotStreams(videoUrl = "v", audioUrl = "x", videoManifest = "vm", audioManifest = "am")
        slots.assign(2, index = 5, videoId = "a", streams)

        slots.clear(2)

        assertThat(slots.owns(2, index = 5, videoId = "a")).isFalse()
        assertThat(slots.streams[2]).isNull()
    }
}
