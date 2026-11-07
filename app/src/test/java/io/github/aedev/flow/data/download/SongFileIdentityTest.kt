package io.github.aedev.flow.data.download

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.innertube.models.response.PlayerResponse.StreamingData.Format
import org.junit.Test

class SongFileIdentityTest {
    private val opus = format(itag = 251, lastModified = 111)
    private val aac = format(itag = 140, lastModified = 222)

    @Test
    fun `a song with nothing cached takes the best stream`() {
        assertThat(streamToContinue(null, aac, listOf(opus, aac))).isSameInstanceAs(aac)
    }

    @Test
    fun `a song cached in Opus stays in Opus when mobile data would pick AAC`() {
        assertThat(streamToContinue(opus.fileId(), aac, listOf(opus, aac))).isSameInstanceAs(opus)
    }

    @Test
    fun `a song cached in AAC stays in AAC when Wi-Fi would pick Opus`() {
        assertThat(streamToContinue(aac.fileId(), opus, listOf(opus, aac))).isSameInstanceAs(aac)
    }

    @Test
    fun `the same file through another client continues without a search`() {
        val otherClient = format(itag = 251, lastModified = 111, url = "https://other")
        assertThat(streamToContinue(opus.fileId(), otherClient, emptyList())).isSameInstanceAs(otherClient)
    }

    @Test
    fun `a re-encoded file under the same itag can't continue`() {
        val reencoded = format(itag = 251, lastModified = 999)
        assertThat(streamToContinue(opus.fileId(), reencoded, listOf(reencoded))).isNull()
    }

    @Test
    fun `another language under the same itag can't continue`() {
        val english = format(itag = 251, lastModified = 111, xtags = "en")
        val spanish = format(itag = 251, lastModified = 111, xtags = "es")
        assertThat(streamToContinue(english.fileId(), spanish, listOf(spanish))).isNull()
    }

    @Test
    fun `a cached file offered with no url can't continue`() {
        val noUrl = format(itag = 251, lastModified = 111, url = null)
        assertThat(streamToContinue(opus.fileId(), aac, listOf(noUrl, aac))).isNull()
    }

    private fun format(
        itag: Int,
        lastModified: Long,
        xtags: String? = null,
        url: String? = "https://stream",
    ) = Format(
        itag = itag,
        url = url,
        mimeType = "audio/webm",
        bitrate = 128_000,
        width = null,
        height = null,
        contentLength = null,
        quality = "tiny",
        fps = null,
        qualityLabel = null,
        averageBitrate = null,
        audioQuality = null,
        approxDurationMs = null,
        audioSampleRate = null,
        audioChannels = null,
        loudnessDb = null,
        lastModified = lastModified,
        signatureCipher = null,
        xtags = xtags,
    )
}
