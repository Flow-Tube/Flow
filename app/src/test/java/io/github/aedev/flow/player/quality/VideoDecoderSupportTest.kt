package io.github.aedev.flow.player.quality

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VideoDecoderSupportTest {
    private val hardware = DecoderAnswer(hardware = true, supported = true)
    private val software = DecoderAnswer(hardware = false, supported = true)
    private val refuses = DecoderAnswer(hardware = true, supported = false)

    @Test
    fun `a hardware decoder that takes the stream is enough at any size`() {
        assertThat(decodableForAuto(listOf(hardware), 3840, 2160, 60)).isTrue()
    }

    @Test
    fun `software alone is trusted up to 1080p at 30 fps, in either orientation`() {
        assertThat(decodableForAuto(listOf(software), 1920, 1080, 30)).isTrue()
        assertThat(decodableForAuto(listOf(software), 1080, 1920, 30)).isTrue()
        assertThat(decodableForAuto(listOf(software), 1920, 1080, 60)).isFalse()
        assertThat(decodableForAuto(listOf(software), 2560, 1440, 30)).isFalse()
    }

    @Test
    fun `decoders that refuse the profile or size do not count`() {
        assertThat(decodableForAuto(listOf(refuses), 1280, 720, 30)).isFalse()
        assertThat(decodableForAuto(listOf(refuses, software), 1280, 720, 30)).isTrue()
        assertThat(decodableForAuto(emptyList(), 1280, 720, 30)).isFalse()
    }
}
