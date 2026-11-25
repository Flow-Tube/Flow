package io.github.aedev.flow.player.subtitle

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.innertube.models.response.PlayerResponse
import io.github.aedev.flow.player.stream.CaptionTrackResolver
import io.github.aedev.flow.player.stream.CaptionsInitialState
import org.junit.Test

class AudioCaptionsTest {
    private fun track(
        vssId: String,
        language: String,
        name: String,
        kind: String? = null,
    ) = PlayerResponse.Captions.CaptionTrack(
        baseUrl = "https://yt/timedtext?v=x&lang=$language&vss=$vssId",
        name = PlayerResponse.Captions.Text(runs = listOf(PlayerResponse.Captions.Text.Run(name))),
        vssId = vssId,
        languageCode = language,
        kind = kind,
    )

    private fun audio(
        id: String?,
        indices: List<Int>,
        default: Int?,
        state: String,
    ) = PlayerResponse.Captions.AudioTrack(
        captionTrackIndices = indices,
        defaultCaptionTrackIndex = default,
        hasDefaultTrack = default != null,
        audioTrackId = id,
        captionsInitialState = "CAPTIONS_INITIAL_STATE_$state",
    )

    // Index 0 is the original English, 1 its auto-generated twin, 2 the Spanish dub's own transcript.
    private val dubbedVideo =
        CaptionTrackResolver.resolveTracklist(
            PlayerResponse.Captions.PlayerCaptionsTracklistRenderer(
                captionTracks =
                    listOf(
                        track(".en", "en", "English"),
                        track("a.en", "en", "English (auto-generated)", kind = "asr"),
                        track("a.es", "es", "Spanish (auto-generated)", kind = "asr"),
                    ),
                audioTracks =
                    listOf(
                        audio("en.4", listOf(0, 1), default = 0, state = "OFF_RECOMMENDED"),
                        audio("es.3", listOf(0, 2), default = 0, state = "ON_RECOMMENDED"),
                    ),
                defaultAudioTrackIndex = 0,
            ),
        )

    @Test
    fun `the list follows the audio track playing`() {
        assertThat(AudioCaptions.visible(dubbedVideo, "en.4").map { it.trackId }).containsExactly(".en", "a.en").inOrder()
        assertThat(AudioCaptions.visible(dubbedVideo, "es.3").map { it.trackId }).containsExactly(".en", "a.es").inOrder()
    }

    @Test
    fun `a dub recommends its default caption and the original does not`() {
        assertThat(AudioCaptions.recommended(dubbedVideo, "es.3")?.trackId).isEqualTo(".en")
        assertThat(AudioCaptions.recommended(dubbedVideo, "en.4")).isNull()
    }

    @Test
    fun `an unknown or unlisted audio track falls back to YouTube's default track`() {
        assertThat(AudioCaptions.visible(dubbedVideo, null).map { it.trackId }).containsExactly(".en", "a.en").inOrder()
        assertThat(AudioCaptions.visible(dubbedVideo, "fr.3").map { it.trackId }).containsExactly(".en", "a.en").inOrder()
    }

    @Test
    fun `captions YouTube tied to no audio track are always listed`() {
        val translated = dubbedVideo.first().copy(url = "https://yt/timedtext?v=x&tlang=de", audioLinks = emptyList(), trackId = null)

        assertThat(AudioCaptions.visible(dubbedVideo + translated, "es.3")).contains(translated)
    }

    @Test
    fun `without a mapping nothing is filtered and nothing is recommended`() {
        val plain = dubbedVideo.map { it.copy(audioLinks = emptyList()) }

        assertThat(AudioCaptions.visible(plain, "es.3")).isEqualTo(plain)
        assertThat(AudioCaptions.recommended(plain, "es.3")).isNull()
    }

    @Test
    fun `required captions are recommended too`() {
        val required =
            CaptionTrackResolver.resolveTracklist(
                PlayerResponse.Captions.PlayerCaptionsTracklistRenderer(
                    captionTracks = listOf(track(".en", "en", "English")),
                    audioTracks = listOf(audio(null, listOf(0), default = 0, state = "ON_REQUIRED")),
                    defaultAudioTrackIndex = 0,
                ),
            )

        assertThat(required.single().audioLinks.single().initialState).isEqualTo(CaptionsInitialState.ON_REQUIRED)
        assertThat(AudioCaptions.recommended(required, null)?.trackId).isEqualTo(".en")
    }
}
