package io.github.aedev.flow.ui.components.shared.card

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.DeArrowResult
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.ui.components.DeArrowLookup
import org.junit.Test

class CardThumbnailPolicyTest {
    private val uploaders = "https://i.ytimg.com/vi/abc123def45/hq720.jpg"
    private val frame = "https://dearrow-thumb.ajay.app/api/v1/getThumbnail?videoID=abc123def45&time=12.5"

    private fun video(
        id: String = "abc123def45",
        isShort: Boolean = false,
        isLive: Boolean = false,
        isUpcoming: Boolean = false,
    ) = Video(
        id = id,
        title = "Title",
        channelName = "Channel",
        channelId = "UC1",
        thumbnailUrl = uploaders,
        duration = 600,
        viewCount = 1,
        uploadDate = "",
        isShort = isShort,
        isLive = isLive,
        isUpcoming = isUpcoming,
    )

    private val pending = DeArrowLookup(result = null, answered = false)
    private val nothing = DeArrowLookup(result = null, answered = true)

    @Test
    fun `thumbnails only holds the card empty until DeArrow answers`() {
        assertThat(
            CardThumbnailPolicy.resolve(video(), pending, thumbnailsOnly = true),
        ).isEqualTo(CardThumbnail(url = null, youTubeFrames = true))
    }

    @Test
    fun `thumbnails only shows DeArrow's frame once it answers`() {
        val answered = DeArrowLookup(DeArrowResult(thumbnailUrl = frame, youTubeFrames = true), answered = true)

        assertThat(
            CardThumbnailPolicy.resolve(video(), answered, thumbnailsOnly = true),
        ).isEqualTo(CardThumbnail(frame, youTubeFrames = true))
    }

    @Test
    fun `thumbnails only turns no answer, a failure or a kept original into youtube's frame`() {
        val keptTitle = DeArrowLookup(DeArrowResult(title = "Calm title"), answered = true)

        assertThat(CardThumbnailPolicy.resolve(video(), nothing, thumbnailsOnly = true).youTubeFrames).isTrue()
        assertThat(CardThumbnailPolicy.resolve(video(), keptTitle, thumbnailsOnly = true).youTubeFrames).isTrue()
    }

    @Test
    fun `shorts, live, upcoming and device files keep their own image`() {
        listOf(video(isShort = true), video(isLive = true), video(isUpcoming = true), video(id = "local_42")).forEach { kept ->
            assertThat(
                CardThumbnailPolicy.resolve(kept, pending, thumbnailsOnly = true),
            ).isEqualTo(CardThumbnail(uploaders, youTubeFrames = false))
        }
    }

    @Test
    fun `with the switch off the card shows the uploader's thumbnail while DeArrow is asked`() {
        assertThat(
            CardThumbnailPolicy.resolve(video(), pending, thumbnailsOnly = false),
        ).isEqualTo(CardThumbnail(uploaders, youTubeFrames = false))
    }

    @Test
    fun `with the switch off a DeArrow frame still falls back to youtube's frames`() {
        val answered = DeArrowLookup(DeArrowResult(thumbnailUrl = frame, youTubeFrames = true), answered = true)

        assertThat(
            CardThumbnailPolicy.resolve(video(), answered, thumbnailsOnly = false),
        ).isEqualTo(CardThumbnail(frame, youTubeFrames = true))
    }
}
