package io.github.aedev.flow.ui.screens.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackSpeedPolicyTest {
    @Test
    fun `with every option off the speed is left alone`() {
        assertThat(PlaybackSpeedPolicy.decide(isMusic = true, null, false, false, 1.5f)).isEqualTo(SpeedDecision.Keep)
    }

    @Test
    fun `remembered speed applies to non-music videos`() {
        assertThat(PlaybackSpeedPolicy.decide(isMusic = false, null, true, true, 1.5f)).isEqualTo(SpeedDecision.Remembered(1.5f))
    }

    @Test
    fun `music plays at normal speed when asked`() {
        assertThat(PlaybackSpeedPolicy.decide(isMusic = true, null, true, true, 1.5f)).isEqualTo(SpeedDecision.Override(1f))
    }

    @Test
    fun `a channel speed comes first`() {
        assertThat(PlaybackSpeedPolicy.decide(isMusic = true, 1.25f, true, true, 2f)).isEqualTo(SpeedDecision.Override(1.25f))
    }

    @Test
    fun `music is recognised from the music type, the category or where it was opened`() {
        assertThat(PlaybackSpeedPolicy.isMusic("MUSIC_VIDEO_TYPE_OMV", null, false)).isTrue()
        assertThat(PlaybackSpeedPolicy.isMusic(null, "Music", false)).isTrue()
        assertThat(PlaybackSpeedPolicy.isMusic(null, null, true)).isTrue()
        assertThat(PlaybackSpeedPolicy.isMusic(null, "Gaming", false)).isFalse()
    }

    @Test
    fun `a podcast episode is not music, even opened in the music player`() {
        assertThat(PlaybackSpeedPolicy.isMusic("MUSIC_VIDEO_TYPE_PODCAST_EPISODE", null, true)).isFalse()
        assertThat(PlaybackSpeedPolicy.isMusic("MUSIC_VIDEO_TYPE_PODCAST_EPISODE", "Music", false)).isFalse()
        assertThat(PlaybackSpeedPolicy.isMusic("MUSIC_VIDEO_TYPE_ATV", null, false)).isTrue()
    }
}
