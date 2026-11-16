package io.github.aedev.flow.data.repository

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.DeArrowContent
import io.github.aedev.flow.data.model.DeArrowThumbnail
import io.github.aedev.flow.data.model.DeArrowTitle
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test

class DeArrowBrandingPolicyTest {
    private fun resolve(
        content: DeArrowContent,
        duration: Int = 600,
        useRandomFrame: Boolean = true,
        live: Boolean = false,
    ) = DeArrowBrandingPolicy.resolve(content, "abc123def45", duration, useRandomFrame, live)

    private fun frameTime(url: String?): Double? = url?.toHttpUrl()?.queryParameter("time")?.toDouble()

    @Test
    fun `a video nobody submitted for shows the frame at the random time`() {
        val result = resolve(DeArrowContent(randomTime = 0.25, videoDuration = 400.0))

        assertThat(result?.title).isNull()
        assertThat(frameTime(result?.thumbnailUrl)).isEqualTo(100.0)
        assertThat(result?.thumbnailUrl?.toHttpUrl()?.queryParameter("videoID")).isEqualTo("abc123def45")
    }

    @Test
    fun `a missing server duration falls back to the card's`() {
        val result = resolve(DeArrowContent(randomTime = 0.5, videoDuration = null), duration = 300)

        assertThat(frameTime(result?.thumbnailUrl)).isEqualTo(150.0)
    }

    @Test
    fun `no duration anywhere still asks for youtube's frame instead of the uploader's thumbnail`() {
        val result = resolve(DeArrowContent(randomTime = 0.5), duration = 0)

        assertThat(result?.thumbnailUrl).isNull()
        assertThat(result?.youTubeFrames).isTrue()
    }

    @Test
    fun `a frame from DeArrow falls back to youtube's frames`() {
        assertThat(resolve(DeArrowContent(randomTime = 0.5, videoDuration = 100.0))?.youTubeFrames).isTrue()
        assertThat(resolve(DeArrowContent(thumbnails = listOf(DeArrowThumbnail(timestamp = 4.0, votes = 1))))?.youTubeFrames).isTrue()
    }

    @Test
    fun `a title alone keeps the uploader's thumbnail`() {
        val result = resolve(DeArrowContent(titles = listOf(DeArrowTitle("Calm title", votes = 1))), useRandomFrame = false)

        assertThat(result?.youTubeFrames).isFalse()
    }

    @Test
    fun `the random frame can be switched off`() {
        assertThat(resolve(DeArrowContent(randomTime = 0.5, videoDuration = 100.0), useRandomFrame = false)).isNull()
    }

    @Test
    fun `live and upcoming videos get no random frame`() {
        assertThat(resolve(DeArrowContent(randomTime = 0.5, videoDuration = 100.0), live = true)).isNull()
    }

    @Test
    fun `the server's first submission wins`() {
        val content =
            DeArrowContent(
                titles = listOf(DeArrowTitle("Locked title", votes = 1, locked = true), DeArrowTitle("Popular title", votes = 9)),
                thumbnails =
                    listOf(
                        DeArrowThumbnail(timestamp = 3.5, votes = 2, locked = true),
                        DeArrowThumbnail(timestamp = 80.0, votes = 7),
                    ),
                randomTime = 0.5,
                videoDuration = 100.0,
            )

        val result = resolve(content)

        assertThat(result?.title).isEqualTo("Locked title")
        assertThat(frameTime(result?.thumbnailUrl)).isEqualTo(3.5)
    }

    @Test
    fun `a winning original keeps youtube's title and thumbnail`() {
        val content =
            DeArrowContent(
                titles = listOf(DeArrowTitle("Original", votes = 5, original = true), DeArrowTitle("Other", votes = 1)),
                thumbnails = listOf(DeArrowThumbnail(votes = 5, original = true), DeArrowThumbnail(timestamp = 10.0, votes = 1)),
                randomTime = 0.5,
                videoDuration = 100.0,
            )

        assertThat(resolve(content)).isNull()
    }

    @Test
    fun `a downvoted unlocked submission is skipped`() {
        val content =
            DeArrowContent(
                titles = listOf(DeArrowTitle("Bad", votes = -2), DeArrowTitle("Good", votes = 0)),
                thumbnails = listOf(DeArrowThumbnail(timestamp = 5.0, votes = -1)),
                randomTime = 0.1,
                videoDuration = 100.0,
            )

        val result = resolve(content)

        assertThat(result?.title).isEqualTo("Good")
        assertThat(frameTime(result?.thumbnailUrl)).isWithin(1e-9).of(10.0)
    }

    @Test
    fun `case markers are stripped from a submitted title`() {
        val result = resolve(DeArrowContent(titles = listOf(DeArrowTitle(">iPhone review", votes = 1))), useRandomFrame = false)

        assertThat(result?.title).isEqualTo("iPhone review")
        assertThat(result?.thumbnailUrl).isNull()
    }
}
