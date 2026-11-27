package io.github.aedev.flow.player.audio

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.innertube.models.response.PlayerResponse
import org.junit.Test

class LoudnessGainTest {
    private fun config(
        loudnessDb: Double? = null,
        perceptual: Double? = null,
        target: Double? = null,
    ) = PlayerResponse.PlayerConfig.AudioConfig(loudnessDb = loudnessDb, perceptualLoudnessDb = perceptual, loudnessTargetLkfs = target)

    @Test
    fun `perceptual loudness is measured against the target`() {
        assertThat(LoudnessGain.gainDb(config(loudnessDb = 9.0, perceptual = -8.0), null)).isEqualTo(-6f)
        assertThat(LoudnessGain.gainDb(config(perceptual = -10.0, target = -16.0), null)).isEqualTo(-6f)
    }

    @Test
    fun `the playing format's own level wins over the response's`() {
        assertThat(LoudnessGain.gainDb(config(loudnessDb = 4.5, perceptual = -9.5), 9.0)).isEqualTo(-9f)
        assertThat(LoudnessGain.gainDb(null, 3.0)).isEqualTo(-3f)
    }

    @Test
    fun `a format with only its absolute level is measured against the target`() {
        assertThat(LoudnessGain.gainDb(config(loudnessDb = 4.5, target = -14.0), null, -11.0)).isEqualTo(-3f)
    }

    @Test
    fun `loudnessDb is used when there is no perceptual level and no format level`() {
        assertThat(LoudnessGain.gainDb(config(loudnessDb = 4.5), null)).isEqualTo(-4.5f)
    }

    @Test
    fun `a dub is not turned down by the original track's level`() {
        // A dubbed video's response: audioConfig describes the original, each dub format its own track.
        val response = config(loudnessDb = 1.66, perceptual = -12.34, target = -14.0)

        assertThat(LoudnessGain.gainDb(response, 0.0, -14.0)).isEqualTo(0f)
        assertThat(LoudnessGain.gainDb(response, 1.66, -12.34)!!.toDouble()).isWithin(1e-4).of(-1.66)
        assertThat(LoudnessGain.gainDb(response, null)!!.toDouble()).isWithin(1e-4).of(-1.66)
    }

    @Test
    fun `a target other than minus 14 is met from absolute levels, not from loudnessDb`() {
        // loudnessDb is relative to -14 LKFS, so it would leave a -16 target 2 dB too loud.
        val response =
            PlayerResponse.PlayerConfig.AudioConfig(
                loudnessDb = 4.0,
                perceptualLoudnessDb = -10.0,
                loudnessTargetLkfs = -16.0,
                trackAbsoluteLoudnessLkfs = -10.0,
            )

        assertThat(LoudnessGain.gainDb(response, 4.0, -10.0)).isEqualTo(-6f)
        assertThat(LoudnessGain.gainDb(response, null)).isEqualTo(-6f)
        assertThat(LoudnessGain.gainDb(response.copy(trackAbsoluteLoudnessLkfs = null), null)).isEqualTo(-6f)
    }

    @Test
    fun `per-format loudness turned off falls back to the response's loudnessDb`() {
        val response =
            PlayerResponse.PlayerConfig.AudioConfig(loudnessDb = 2.0, perceptualLoudnessDb = null, enablePerFormatLoudness = false)

        assertThat(LoudnessGain.gainDb(response, 5.0)).isEqualTo(-2f)
        assertThat(LoudnessGain.gainDb(response.copy(enablePerFormatLoudness = true), 5.0)).isEqualTo(-5f)
    }

    @Test
    fun `quiet tracks are never boosted and loud ones stop at minus 20 dB`() {
        assertThat(LoudnessGain.gainDb(config(loudnessDb = -7.0), null)).isEqualTo(0f)
        assertThat(LoudnessGain.gainDb(config(loudnessDb = 35.0), null)).isEqualTo(-20f)
    }

    @Test
    fun `no metadata means no gain`() {
        assertThat(LoudnessGain.gainDb(null, null)).isNull()
        assertThat(LoudnessGain.volume(null)).isEqualTo(1f)
    }

    @Test
    fun `minus 6 dB is about half the amplitude`() {
        assertThat(LoudnessGain.volume(-6f).toDouble()).isWithin(0.01).of(0.501)
    }
}
