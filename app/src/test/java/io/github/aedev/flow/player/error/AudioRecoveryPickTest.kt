package io.github.aedev.flow.player.error

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import java.util.Locale

class AudioRecoveryPickTest {
    private fun audio(
        id: String,
        languageTag: String,
        trackType: AudioTrackType,
        format: MediaFormat = MediaFormat.M4A,
        bitrate: Int = 129_000,
    ): AudioStream =
        AudioStream
            .Builder()
            .setId(id)
            .setContent("https://example.invalid/$id", true)
            .setMediaFormat(format)
            .setAverageBitrate(bitrate)
            .setAudioTrackType(trackType)
            .setAudioLocale(Locale.forLanguageTag(languageTag))
            .build()

    private val originalAac = audio("140-en", "en", AudioTrackType.ORIGINAL)
    private val originalOpus = audio("251-en", "en", AudioTrackType.ORIGINAL, MediaFormat.WEBMA_OPUS, bitrate = 135_000)
    private val arabicDub = audio("140-ar", "ar", AudioTrackType.DUBBED)
    private val germanDub = audio("140-de", "de", AudioTrackType.DUBBED)
    private val spanishDubAac = audio("140-es", "es", AudioTrackType.DUBBED)
    private val spanishDubOpus = audio("251-es", "es", AudioTrackType.DUBBED, MediaFormat.WEBMA_OPUS)

    private fun failedUrls(vararg streams: AudioStream) = streams.map { it.getContent() }.toSet()

    @Test
    fun `a failed dub with no other format is not replaced by another dub`() {
        val pick = AudioRecoveryPick.pick(listOf(arabicDub, germanDub, spanishDubAac), spanishDubAac, failedUrls(spanishDubAac))

        assertThat(pick).isNull()
    }

    @Test
    fun `a failed original with only dubs beside it is not replaced by a dub`() {
        val pick = AudioRecoveryPick.pick(listOf(arabicDub, originalAac, germanDub), originalAac, failedUrls(originalAac))

        assertThat(pick).isNull()
    }

    @Test
    fun `a failed AAC stream moves to the Opus format of the same track`() {
        val streams = listOf(arabicDub, originalAac, germanDub, originalOpus)

        val pick = AudioRecoveryPick.pick(streams, originalAac, failedUrls(originalAac))

        assertThat(pick).isSameInstanceAs(originalOpus)
    }

    @Test
    fun `a failed Opus stream moves to the AAC format of the same track`() {
        val streams = listOf(spanishDubOpus, arabicDub, spanishDubAac, originalOpus)

        val pick = AudioRecoveryPick.pick(streams, spanishDubOpus, failedUrls(spanishDubOpus))

        assertThat(pick).isSameInstanceAs(spanishDubAac)
    }

    @Test
    fun `the other codec wins over a higher bitrate in the failed codec`() {
        val betterAac = audio("141-en", "en", AudioTrackType.ORIGINAL, bitrate = 256_000)
        val streams = listOf(originalAac, betterAac, originalOpus)

        val pick = AudioRecoveryPick.pick(streams, originalAac, failedUrls(originalAac))

        assertThat(pick).isSameInstanceAs(originalOpus)
    }

    @Test
    fun `nothing is picked once every format of the track has failed`() {
        val streams = listOf(originalAac, originalOpus, arabicDub, germanDub)

        val pick = AudioRecoveryPick.pick(streams, originalOpus, failedUrls(originalAac, originalOpus))

        assertThat(pick).isNull()
    }

    @Test
    fun `the failed stream is never picked again even before it is recorded as failed`() {
        val pick = AudioRecoveryPick.pick(listOf(originalAac, arabicDub), originalAac, emptySet())

        assertThat(pick).isNull()
    }
}
