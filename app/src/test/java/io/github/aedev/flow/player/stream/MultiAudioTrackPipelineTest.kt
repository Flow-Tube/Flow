package io.github.aedev.flow.player.stream

import io.github.aedev.flow.innertube.models.response.PlayerResponse
import io.github.aedev.flow.player.stream.model.AudioTrackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the multi-audio path from InnerTube formats through the selector rows. The fixture is
 * built in code so the test does not depend on a missing binary or captured network response.
 */
class MultiAudioTrackPipelineTest {
    private val audioFormats =
        buildList {
            add(audioFormat(id = "en.4", name = "English original", original = true, itag = 140))
            add(
                audioFormat(
                    id = "en.4",
                    name = "English original",
                    original = true,
                    itag = 140,
                    isDrc = true,
                ),
            )
            listOf(
                "Arabic" to "ar",
                "Bengali" to "bn",
                "Chinese" to "zh",
                "Czech" to "cs",
                "Dutch" to "nl",
                "French" to "fr",
                "German" to "de",
                "Greek" to "el",
                "Hindi" to "hi",
                "Indonesian" to "id",
                "Italian" to "it",
                "Japanese" to "ja",
                "Korean" to "ko",
                "Malay" to "ms",
                "Polish" to "pl",
                "Portuguese" to "pt",
                "Romanian" to "ro",
                "Russian" to "ru",
                "Spanish" to "es",
                "Swedish" to "sv",
                "Tamil" to "ta",
                "Telugu" to "te",
                "Thai" to "th",
                "Turkish" to "tr",
                "Ukrainian" to "uk",
            ).forEachIndexed { index, (name, language) ->
                add(audioFormat(id = "$language.3", name = name, original = false, itag = 141 + index))
            }
        }

    @Test
    fun `every dubbed track reaches the selector as its own row`() {
        val tracks =
            StreamProcessor.processAudioStreams(
                InnerTubeStreamBridge.convertAudioFormats(audioFormats),
            )

        assertEquals(26, tracks.size)
        assertEquals(26, tracks.mapNotNull { it.audioTrackId }.distinct().size)
    }

    @Test
    fun `exactly one track is reported as the original`() {
        val tracks =
            StreamProcessor.processAudioStreams(
                InnerTubeStreamBridge.convertAudioFormats(audioFormats),
            )

        val originals = tracks.filter { it.audioTrackType == AudioTrackType.ORIGINAL }
        assertEquals(1, originals.size)
        assertEquals("en.4", originals.single().audioTrackId)
    }

    @Test
    fun `dubs are the ones that bypass the default audio path`() {
        val tracks =
            StreamProcessor.processAudioStreams(
                InnerTubeStreamBridge.convertAudioFormats(audioFormats),
            )

        assertEquals(25, tracks.count { StreamProcessor.overridesDefaultAudioTrack(it) })
        assertTrue(
            !StreamProcessor.overridesDefaultAudioTrack(tracks.single { it.audioTrackId == "en.4" }),
        )
    }

    @Test
    fun `the drc copy of the original never wins its track`() {
        // The fixture carries DRC and non-DRC copies of en.4 at near-identical bitrates, so a plain
        // bitrate sort would land on the loudness-flattened one.
        assertTrue(audioFormats.any { it.isDynamicRangeCompressed })

        val converted = InnerTubeStreamBridge.convertAudioFormats(audioFormats)

        assertTrue(converted.none { it.content.contains("drc=True") })
    }

    @Test
    fun `selector rows are labelled with their language`() {
        val options =
            StreamProcessor.toAudioTrackOptions(
                StreamProcessor.processAudioStreams(
                    InnerTubeStreamBridge.convertAudioFormats(audioFormats),
                ),
            )

        assertTrue(options.none { it.label.isBlank() })
        assertTrue(options.any { it.label.contains("original", ignoreCase = true) })
        assertTrue(options.any { it.label.contains("Arabic") })
        assertEquals(options.indices.toList(), options.map { it.index })
    }

    @Test
    fun `a track-poor response is what triggers a top-up`() {
        // The default-track-only shape a client like ANDROID_VR returns.
        val defaultOnly = audioFormats.filter { it.audioTrack?.id == "en.4" }

        assertEquals(1, defaultOnly.mapNotNull { it.audioTrack?.id }.distinct().size)
        assertEquals(26, audioFormats.mapNotNull { it.audioTrack?.id }.distinct().size)
    }

    private fun audioFormat(
        id: String,
        name: String,
        original: Boolean,
        itag: Int,
        isDrc: Boolean = false,
    ) = PlayerResponse.StreamingData.Format(
        itag = itag,
        url = "https://example.invalid/$id/$itag",
        mimeType = "audio/mp4; codecs=\"mp4a.40.2\"",
        bitrate = 130_000,
        width = null,
        height = null,
        contentLength = null,
        quality = "tiny",
        fps = null,
        qualityLabel = null,
        averageBitrate = 130_000,
        audioQuality = "AUDIO_QUALITY_MEDIUM",
        approxDurationMs = "1000",
        audioSampleRate = 44_100,
        audioChannels = 2,
        loudnessDb = null,
        lastModified = null,
        signatureCipher = null,
        audioTrack =
            PlayerResponse.StreamingData.Format.AudioTrack(
                displayName = name,
                id = id,
                isAutoDubbed = !original,
                audioIsDefault = original,
            ),
        isDrc = isDrc,
    )
}
