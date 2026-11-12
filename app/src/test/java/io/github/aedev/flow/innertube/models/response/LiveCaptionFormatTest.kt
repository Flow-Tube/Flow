package io.github.aedev.flow.innertube.models.response

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * A live stream with auto captions lists its caption track among the adaptive formats, with no
 * bitrate. One such entry used to fail the whole `/player` response, so the stream never played.
 */
class LiveCaptionFormatTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }

    private val response =
        """
        {
          "responseContext": {},
          "playabilityStatus": {"status": "OK"},
          "streamingData": {
            "hlsManifestUrl": "https://manifest.googlevideo.com/api/manifest/hls_variant/file/index.m3u8",
            "adaptiveFormats": [
              {"itag": 136, "mimeType": "video/mp4; codecs=\"avc1.4d401f\"", "bitrate": 2500000, "width": 1280, "height": 720, "quality": "hd720"},
              {"itag": 140, "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 130000, "quality": "tiny"},
              {"itag": 386, "mimeType": "text/mp4; codecs=\"unknown\"", "quality": "tiny", "targetDurationSec": 5}
            ]
          }
        }
        """.trimIndent()

    private fun formats() = json.decodeFromString<PlayerResponse>(response).streamingData!!.adaptiveFormats

    @Test
    fun `a caption track with no bitrate does not fail the response`() {
        assertThat(formats().map { it.itag }).containsExactly(136, 140, 386).inOrder()
    }

    @Test
    fun `the caption track is neither audio nor video`() {
        val caption = formats().single { it.itag == 386 }
        assertThat(caption.isText).isTrue()
        assertThat(caption.isAudio).isFalse()
        assertThat(formats().filter { it.isAudio }.map { it.itag }).containsExactly(140)
    }
}
