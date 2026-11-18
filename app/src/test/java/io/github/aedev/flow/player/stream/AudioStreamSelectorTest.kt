package io.github.aedev.flow.player.stream

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.MusicAudioQuality
import io.github.aedev.flow.player.stream.PlaybackTestStreams.m4a
import io.github.aedev.flow.player.stream.PlaybackTestStreams.opus
import org.junit.Test

/** Which audio plays: the preferred language else the original track, then the bitrate the quality setting asks for. */
class AudioStreamSelectorTest {
    private val enHigh = opus(bitrate = 160_000, trackId = "en.4", trackName = "English original", isDefault = true)
    private val enLow = opus(bitrate = 50_000, trackId = "en.4", trackName = "English original", isDefault = true)
    private val enM4a = m4a(bitrate = 129_000, trackId = "en.4", trackName = "English original", isDefault = true)
    private val frDub = opus(bitrate = 170_000, trackId = "fr.3", trackName = "French", isDefault = false, isAutoDubbed = true)
    private val ptBrDub =
        opus(bitrate = 140_000, trackId = "pt-BR.3", trackName = "Portuguese (Brazil)", isDefault = false, isAutoDubbed = true)
    private val all = listOf(enLow, frDub, enM4a, ptBrDub, enHigh)

    private fun pick(
        language: String,
        quality: MusicAudioQuality = MusicAudioQuality.AUTO,
        streams: List<TestAudioStream> = all,
        filter: ((TestAudioStream) -> Boolean)? = null,
    ) = AudioStreamSelector.selectPreferredAudioStream(streams, language, quality, filter)?.testUrl

    @Test
    fun `no preference or original plays the original track at its best bitrate`() {
        assertThat(pick("")).isEqualTo(enHigh.testUrl)
        assertThat(pick("original")).isEqualTo(enHigh.testUrl)
        assertThat(pick(" Original ")).isEqualTo(enHigh.testUrl)
    }

    @Test
    fun `a preferred language plays its dub, regional forms included`() {
        assertThat(pick("fr")).isEqualTo(frDub.testUrl)
        assertThat(pick("pt")).isEqualTo(ptBrDub.testUrl)
    }

    @Test
    fun `a language the video does not offer falls back to the original track`() {
        assertThat(pick("de")).isEqualTo(enHigh.testUrl)
    }

    @Test
    fun `the quality setting picks the highest, nearest 128 kbps or lowest bitrate`() {
        assertThat(pick("", MusicAudioQuality.HIGH)).isEqualTo(enHigh.testUrl)
        assertThat(pick("", MusicAudioQuality.MEDIUM)).isEqualTo(enM4a.testUrl)
        assertThat(pick("", MusicAudioQuality.LOW)).isEqualTo(enLow.testUrl)
    }

    @Test
    fun `a compatibility filter narrows the choice unless nothing passes it`() {
        val mp4Only: (TestAudioStream) -> Boolean = { it.testItag == 140 }
        assertThat(pick("", filter = mp4Only)).isEqualTo(enM4a.testUrl)
        assertThat(pick("", filter = { false })).isEqualTo(enHigh.testUrl)
    }

    @Test
    fun `audio without track metadata is treated as the original`() {
        val untagged = opus(bitrate = 120_000)

        assertThat(pick("", streams = listOf(frDub, untagged))).isEqualTo(untagged.testUrl)
    }

    @Test
    fun `nothing to choose from is nothing`() {
        assertThat(pick("", streams = emptyList())).isNull()
    }
}
