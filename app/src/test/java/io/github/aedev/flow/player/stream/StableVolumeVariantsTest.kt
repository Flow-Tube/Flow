package io.github.aedev.flow.player.stream

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.innertube.models.response.PlayerResponse
import io.github.aedev.flow.innertube.models.response.PlayerResponse.StreamingData.Format
import io.github.aedev.flow.player.sabr.integration.SabrStreamInfo
import kotlinx.serialization.json.Json
import org.junit.Test

class StableVolumeVariantsTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    private val formats: List<Format> =
        javaClass.classLoader!!
            .getResourceAsStream("player_web_vod.json")!!
            .bufferedReader()
            .use { json.decodeFromString<PlayerResponse>(it.readText()) }
            .streamingData!!
            .adaptiveFormats
            .filter { it.isAudio }
            .map { it.copy(url = "https://example.invalid/${it.itag}/${it.lastModified}") }

    private val plain = InnerTubeStreamBridge.convertAudioFormats(formats)

    private fun Format.isPlain() = !isDynamicRangeCompressed && xtags.isNullOrEmpty()

    private fun urlOf(predicate: (Format) -> Boolean) = formats.single(predicate).url

    @Test
    fun `stable volume is available only for a track with a drc copy`() {
        val opus = plain.single { it.id == "251" }

        assertThat(StableVolumeVariants.isAvailable(formats, opus)).isTrue()
        assertThat(StableVolumeVariants.isAvailable(formats.filterNot { it.isDynamicRangeCompressed }, opus)).isFalse()
        assertThat(StableVolumeVariants.isAvailable(emptyList(), opus)).isFalse()
    }

    @Test
    fun `streams move to the drc copy of their own itag and back`() {
        val stable = StableVolumeVariants.streams(plain, formats, stable = true)

        assertThat(stable.map { it.id }).isEqualTo(plain.map { it.id })
        assertThat(stable.map { it.content })
            .containsExactly(
                urlOf { it.itag == 140 && it.isDynamicRangeCompressed },
                urlOf { it.itag == 251 && it.isDynamicRangeCompressed },
            )
        assertThat(StableVolumeVariants.streams(stable, formats, stable = false).map { it.content })
            .containsExactly(urlOf { it.itag == 140 && it.isPlain() }, urlOf { it.itag == 251 && it.isPlain() })
    }

    @Test
    fun `a stream already on the asked copy is kept as it is`() {
        val opus = plain.single { it.id == "251" }

        assertThat(StableVolumeVariants.stream(opus, formats, stable = false)).isSameInstanceAs(opus)
    }

    @Test
    fun `a sabr session asks for the drc copy's lmt and xtags`() {
        val opusPlain = formats.single { it.itag == 251 && it.isPlain() }
        val opusDrc = formats.single { it.itag == 251 && it.isDynamicRangeCompressed }
        val session =
            SabrStreamInfo(
                streamingUrl = "https://example.invalid/sabr",
                audioItag = 251,
                audioLmt = opusPlain.lastModified!!,
                videoItag = 313,
                videoLmt = 1L,
                durationMs = 1_000L,
            )

        val stable = StableVolumeVariants.sabr(session, formats, stable = true)!!
        assertThat(stable.audioItag).isEqualTo(251)
        assertThat(stable.audioLmt).isEqualTo(opusDrc.lastModified)
        assertThat(stable.audioXtags).isEqualTo(opusDrc.xtags)

        assertThat(StableVolumeVariants.sabr(stable, formats, stable = false)).isEqualTo(session)
        assertThat(StableVolumeVariants.sabr(session, formats, stable = false)).isSameInstanceAs(session)
    }
}
