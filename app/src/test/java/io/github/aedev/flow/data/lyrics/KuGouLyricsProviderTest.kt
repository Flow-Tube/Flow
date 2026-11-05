package io.github.aedev.flow.data.lyrics

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.lyrics.KuGouLyricsProvider.Companion.isInstrumentalNotice
import org.junit.Test

class KuGouLyricsProviderTest {
    @Test
    fun `the instrumental notice reads as no lyrics`() {
        assertThat(isInstrumentalNotice(LyricsUtils.parseLyrics("[00:01.58]纯音乐，请欣赏"))).isTrue()
        assertThat(isInstrumentalNotice(LyricsUtils.parseLyrics("[00:01.58]纯音乐,请欣赏"))).isTrue()
    }

    @Test
    fun `lyrics that contain the notice are kept`() {
        assertThat(isInstrumentalNotice(LyricsUtils.parseLyrics("[00:01.00]纯音乐，请欣赏\n[00:05.00]First"))).isFalse()
        assertThat(isInstrumentalNotice(emptyList())).isFalse()
    }
}
