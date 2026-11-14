package io.github.aedev.flow.innertube.pages.channel

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChannelSearchTest {
    @Test
    fun `an empty search in another language is asked again in English (2057)`() {
        assertThat(shouldSearchChannelInEnglish(videoCount = 0, hl = "es")).isTrue()
        assertThat(shouldSearchChannelInEnglish(videoCount = 0, hl = "pt-BR")).isTrue()
    }

    @Test
    fun `a search that found videos, or was already in English, is not repeated`() {
        assertThat(shouldSearchChannelInEnglish(videoCount = 3, hl = "es")).isFalse()
        assertThat(shouldSearchChannelInEnglish(videoCount = 0, hl = "en")).isFalse()
        assertThat(shouldSearchChannelInEnglish(videoCount = 0, hl = "en-GB")).isFalse()
    }
}
