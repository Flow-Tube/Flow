package io.github.aedev.flow.player.audio

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.innertube.models.response.PlayerResponse
import io.github.aedev.flow.player.state.EnhancedPlayerState
import io.github.aedev.flow.player.stream.model.AudioStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test

class TrackLoudnessTest {
    private val dubbedVideo =
        PlayerResponse.PlayerConfig.AudioConfig(
            loudnessDb = 1.66,
            perceptualLoudnessDb = -12.34,
            loudnessTargetLkfs = -14.0,
        )
    private val original = track("en.4", loudnessDb = 1.66, absoluteLkfs = -12.34)
    private val spanishDub = track("es.3", loudnessDb = 0.0, absoluteLkfs = -14.0)

    private val manager =
        AudioFeaturesManager(CoroutineScope(Dispatchers.Unconfined), MutableStateFlow(EnhancedPlayerState())).apply {
            toggleVolumeNormalization(isEnabled = true, context = null)
        }

    @Test
    fun `a video opened on its dub gets the dub's level`() {
        manager.setLoudness("video", dubbedVideo, spanishDub)

        assertThat(manager.appliedLoudnessDb).isEqualTo(0f)
    }

    @Test
    fun `switching track re-reads the level from the new track`() {
        manager.setLoudness("video", dubbedVideo, original)
        assertThat(manager.appliedLoudnessDb!!.toDouble()).isWithin(1e-4).of(-1.66)

        manager.onAudioStreamChanged("video", spanishDub)
        assertThat(manager.appliedLoudnessDb).isEqualTo(0f)

        manager.onAudioStreamChanged("video", original)
        assertThat(manager.appliedLoudnessDb!!.toDouble()).isWithin(1e-4).of(-1.66)
    }

    @Test
    fun `a track change on another video leaves the level alone`() {
        manager.setLoudness("video", dubbedVideo, original)

        manager.onAudioStreamChanged("other", spanishDub)

        assertThat(manager.appliedLoudnessDb!!.toDouble()).isWithin(1e-4).of(-1.66)
    }

    @Test
    fun `a downloaded copy plays at the gain saved with it`() {
        manager.setLoudness("video", dubbedVideo, original)

        manager.setStoredLoudness("video", -4.5f)
        assertThat(manager.appliedLoudnessDb).isEqualTo(-4.5f)

        manager.onAudioStreamChanged("video", spanishDub)
        assertThat(manager.appliedLoudnessDb).isEqualTo(-4.5f)

        manager.setStoredLoudness("other", null)
        assertThat(manager.appliedLoudnessDb).isNull()
    }

    private fun track(
        id: String,
        loudnessDb: Double,
        absoluteLkfs: Double,
    ) = AudioStream(
        id = "140",
        content = "https://example.invalid/$id",
        format = null,
        itagItem = null,
        averageBitrate = 128_000,
        audioTrackId = id,
        loudnessDb = loudnessDb,
        trackAbsoluteLoudnessLkfs = absoluteLkfs,
    )
}
