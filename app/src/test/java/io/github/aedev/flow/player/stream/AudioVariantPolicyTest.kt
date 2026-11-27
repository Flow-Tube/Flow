package io.github.aedev.flow.player.stream

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.shorts.selectShortAudioFormat
import io.github.aedev.flow.innertube.models.response.PlayerResponse
import io.github.aedev.flow.innertube.models.response.PlayerResponse.StreamingData.Format
import io.github.aedev.flow.player.sabr.integration.SabrUrlResolver
import kotlinx.serialization.json.Json
import org.junit.Test

class AudioVariantPolicyTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    private fun formats(name: String): List<Format> =
        javaClass.classLoader!!
            .getResourceAsStream(name)!!
            .bufferedReader()
            .use { json.decodeFromString<PlayerResponse>(it.readText()) }
            .streamingData!!
            .adaptiveFormats

    private val fixtures = listOf("player_web_vod.json", "player_visionos_vod.json")

    private fun List<Format>.audio() = filter { it.isAudio }

    private fun List<Format>.video(codec: String) = first { !it.isAudio && it.mimeType.contains(codec) }

    private val Format.isPlain get() = !isDynamicRangeCompressed && xtags.isNullOrEmpty()

    private val Format.isVb get() = !isDynamicRangeCompressed && !xtags.isNullOrEmpty()

    private fun sabrPick(
        all: List<Format>,
        audio: List<Format>,
        codec: String,
        stable: Boolean = false,
        trackId: String? = null,
    ) = SabrUrlResolver.selectBestAudio(audio, all.video(codec), trackId, stable)!!

    @Test
    fun `sabr picks the normal copy of 140 and 251`() {
        for (name in fixtures) {
            val all = formats(name)
            for (audio in listOf(all.audio(), all.audio().reversed())) {
                val aac = sabrPick(all, audio, "avc1")
                val opus = sabrPick(all, audio, "vp9")
                assertThat(aac.itag).isEqualTo(140)
                assertThat(aac.isPlain).isTrue()
                assertThat(opus.itag).isEqualTo(251)
                assertThat(opus.isPlain).isTrue()
            }
        }
    }

    @Test
    fun `sabr picks the drc copy when stable volume is on`() {
        for (name in fixtures) {
            val all = formats(name)
            for (audio in listOf(all.audio(), all.audio().reversed())) {
                val aac = sabrPick(all, audio, "avc1", stable = true)
                val opus = sabrPick(all, audio, "vp9", stable = true)
                assertThat(aac.itag).isEqualTo(140)
                assertThat(aac.isDynamicRangeCompressed).isTrue()
                assertThat(opus.itag).isEqualTo(251)
                assertThat(opus.isDynamicRangeCompressed).isTrue()
            }
        }
    }

    @Test
    fun `shorts pick the normal copy whatever the order`() {
        for (name in fixtures) {
            val audio = formats(name).audio()
            for (list in listOf(audio, audio.reversed())) {
                val pick = selectShortAudioFormat(list, preferredLanguage = "")!!
                assertThat(pick.itag).isEqualTo(140)
                assertThat(pick.isPlain).isTrue()
            }
        }
    }

    @Test
    fun `shorts pick the drc copy when stable volume is on`() {
        for (name in fixtures) {
            val audio = formats(name).audio()
            for (list in listOf(audio, audio.reversed())) {
                val pick = selectShortAudioFormat(list, preferredLanguage = "", stable = true)!!
                assertThat(pick.itag).isEqualTo(140)
                assertThat(pick.isDynamicRangeCompressed).isTrue()
            }
        }
    }

    @Test
    fun `the vb copy is dropped whenever a plain or drc copy exists`() {
        val audio = formats("player_web_vod.json").audio()
        assertThat(audio.count { it.isVb }).isEqualTo(1)

        assertThat(audio.selectAudioVariants().none { it.isVb }).isTrue()
        assertThat(audio.selectAudioVariants(stable = true).none { it.isVb }).isTrue()
        assertThat(audio.selectAudioVariants().map { it.itag }).containsExactly(140, 251).inOrder()
        assertThat(audio.filter { it.isVb }.selectAudioVariants()).hasSize(1)
    }

    @Test
    fun `a group without a drc copy keeps its normal one when stable volume is on`() {
        val plainOnly = formats("player_web_vod.json").audio().filter { it.isPlain }

        assertThat(plainOnly.selectAudioVariants(stable = true)).isEqualTo(plainOnly)
    }

    @Test
    fun `a dubbed track keeps its own copies`() {
        val audio = formats("player_web_vod.json").audio().filterNot { it.isVb }
        val original = audio.map { it.withTrack("en.4") }
        val dub = audio.filterNot { it.isDynamicRangeCompressed }.map { it.withTrack("fr.3") }
        val all = original + dub
        val video = formats("player_web_vod.json")

        val normal = all.selectAudioVariants()
        assertThat(normal.filter { it.audioTrack?.id == "fr.3" }).isEqualTo(dub)
        assertThat(normal.filter { it.audioTrack?.id == "en.4" }.none { it.isDynamicRangeCompressed }).isTrue()

        val stable = all.selectAudioVariants(stable = true)
        assertThat(stable.filter { it.audioTrack?.id == "fr.3" }).isEqualTo(dub)
        assertThat(stable.filter { it.audioTrack?.id == "en.4" }.all { it.isDynamicRangeCompressed }).isTrue()

        val dubPick = sabrPick(video, all, "vp9", stable = true, trackId = "fr.3")
        assertThat(dubPick.audioTrack?.id).isEqualTo("fr.3")
        assertThat(dubPick.isPlain).isTrue()

        assertThat(AudioVariantPolicy.hasStableVariant(all, "en.4")).isTrue()
        assertThat(AudioVariantPolicy.hasStableVariant(all, "fr.3")).isFalse()
    }

    @Test
    fun `stable volume is available exactly when the track has a drc copy`() {
        val audio = formats("player_visionos_vod.json").audio()

        assertThat(AudioVariantPolicy.hasStableVariant(audio, null)).isTrue()
        assertThat(AudioVariantPolicy.hasStableVariant(audio.filterNot { it.isDynamicRangeCompressed }, null)).isFalse()
        assertThat(AudioVariantPolicy.hasStableVariant(emptyList(), null)).isFalse()
    }

    @Test
    fun `video formats pass through untouched`() {
        val all = formats("player_web_vod.json")

        assertThat(all.selectAudioVariants().filterNot { it.isAudio }).isEqualTo(all.filterNot { it.isAudio })
    }

    private fun Format.withTrack(id: String) = copy(audioTrack = Format.AudioTrack(displayName = id, id = id))
}
