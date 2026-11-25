package io.github.aedev.flow.ui.screens.player

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.VideoQuality
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.innertube.models.ResponseContext
import io.github.aedev.flow.innertube.models.Thumbnail
import io.github.aedev.flow.innertube.models.Thumbnails
import io.github.aedev.flow.innertube.models.YouTubeClient
import io.github.aedev.flow.innertube.models.response.PlayerResponse
import io.github.aedev.flow.player.stream.CaptionTrackResolver
import io.github.aedev.flow.player.stream.InnerTubeVideoStreamExtractor
import io.github.aedev.flow.player.stream.ResolvedPlayback
import org.junit.Test

/**
 * Pins what one InnerTube extraction turns into before the player screen writes any of it: the
 * identity the session is armed with, the streams and qualities playback runs on, and the duration
 * every size estimate is derived from.
 *
 * This is the assembly the VOD-from-InnerTube path has always done inline. The fields it feeds into
 * the screen are pinned separately in `PlayerPlaybackReducersTest`, because that path writes a
 * narrower set of them than the merged path does.
 */
class PlaybackStreamPreparerTest {
    private val preparer = PlaybackStreamPreparer()

    @Test
    fun `a VOD extraction becomes Media3 streams, qualities and sizes keyed on the reported duration`() {
        val streams = preparer.assembleVod(VIDEO_ID, cached = null, step = vodStep())

        assertThat(streams.durationSeconds).isEqualTo(300L)
        assertThat(streams.videoStreams).hasSize(3)
        assertThat(streams.audioStreams).hasSize(2)
        assertThat(streams.availableQualities).contains(VideoQuality.AUTO)
        assertThat(streams.availableQualities).contains(VideoQuality.Q_1080P)
        assertThat(streams.availableQualities).contains(VideoQuality.Q_720P)
        assertThat(streams.videoStream).isNotNull()
        assertThat(streams.audioStream).isNotNull()
        assertThat(streams.streamSizes).isNotEmpty()
        assertThat(streams.subtitles).isEmpty()
    }

    @Test
    fun `the preferred quality drives both the selection and the adaptive flag`() {
        val fixed = preparer.assembleVod(VIDEO_ID, cached = null, step = vodStep(preferredQuality = VideoQuality.Q_720P))
        val auto = preparer.assembleVod(VIDEO_ID, cached = null, step = vodStep(preferredQuality = VideoQuality.AUTO))

        assertThat(fixed.isAdaptiveMode).isFalse()
        assertThat(fixed.videoStream?.resolution).isEqualTo("720p")
        assertThat(auto.isAdaptiveMode).isTrue()
    }

    @Test
    fun `the identity prefers what InnerTube reported for the channel, art and length`() {
        val cached = cachedVideo()

        val identity = preparer.assembleVod(VIDEO_ID, cached, vodStep()).identity

        assertThat(identity.channel).isEqualTo("InnerTube channel")
        assertThat(identity.channelId).isEqualTo("UC_innertube")
        assertThat(identity.thumbnail).isEqualTo("https://example.invalid/innertube.jpg")
        assertThat(identity.enrichedVideo.duration).isEqualTo(300)
        assertThat(identity.enrichedVideo.id).isEqualTo(VIDEO_ID)
    }

    @Test
    fun `a title the screen already shows stays, so it does not flash the original (#1230)`() {
        val identity = preparer.assembleVod(VIDEO_ID, cachedVideo(), vodStep(description = "Original description")).identity

        assertThat(identity.title).isEqualTo("Cached title")
        assertThat(identity.enrichedVideo.description).isEqualTo(cachedVideo().description)
    }

    @Test
    fun `with original titles on the player's own title and description win (#1230)`() {
        val original = PlaybackStreamPreparer(originalText = { true })

        val identity = original.assembleVod(VIDEO_ID, cachedVideo(), vodStep(description = "Original description")).identity

        assertThat(identity.title).isEqualTo("InnerTube title")
        assertThat(identity.enrichedVideo.title).isEqualTo("InnerTube title")
        assertThat(identity.enrichedVideo.description).isEqualTo("Original description")
    }

    @Test
    fun `a deep link with no known title starts on the player's title`() {
        val identity = preparer.assembleVod(VIDEO_ID, cached = null, step = vodStep()).identity

        assertThat(identity.title).isEqualTo("InnerTube title")
    }

    @Test
    fun `the identity falls back field by field to the cached video`() {
        val cached = cachedVideo()
        val blankDetails = vodStep(title = "  ", author = "", channelId = "", lengthSeconds = "0", thumbnailUrl = null)

        val identity = preparer.assembleVod(VIDEO_ID, cached, blankDetails).identity

        assertThat(identity.title).isEqualTo("Cached title")
        assertThat(identity.channel).isEqualTo("Cached channel")
        assertThat(identity.channelId).isEqualTo("channel_cached")
        assertThat(identity.thumbnail).isEqualTo("https://example.invalid/cached.jpg")
        assertThat(identity.enrichedVideo.duration).isEqualTo(120)
    }

    @Test
    fun `a VOD with nothing known anywhere lands on an empty title and a resolved thumbnail`() {
        val identity =
            preparer
                .assembleVod(
                    VIDEO_ID,
                    cached = null,
                    step = vodStep(title = null, author = null, channelId = "", lengthSeconds = "0", thumbnailUrl = null),
                ).identity

        assertThat(identity.title).isEmpty()
        assertThat(identity.channel).isEmpty()
        assertThat(identity.channelId).isEmpty()
        assertThat(identity.thumbnail).isNotEmpty()
        assertThat(identity.enrichedVideo.duration).isEqualTo(0)
    }

    @Test
    fun `the embedded avatar list is the cached avatar followed by every other one it carries`() {
        val cached = cachedVideo().copy(channelThumbnailUrl = "a.jpg", channelThumbnailUrls = listOf("b.jpg"))

        val identity = preparer.assembleVod(VIDEO_ID, cached, vodStep()).identity

        assertThat(identity.embeddedAvatarUrls).containsExactly("a.jpg", "b.jpg").inOrder()
        assertThat(preparer.assembleVod(VIDEO_ID, null, vodStep()).identity.embeddedAvatarUrls).isEmpty()
    }

    @Test
    fun `a live extraction carries both manifests through and reports no duration`() {
        val result =
            extraction(
                playerResponse = playerResponse(title = null, author = null, channelId = "", lengthSeconds = "0", thumbnailUrl = null),
                isLive = true,
                liveHlsUrl = "https://example.invalid/live.m3u8",
                liveDashUrl = "https://example.invalid/live.mpd",
            )

        val streams = preparer.assembleLive(VIDEO_ID, cached = null, result = result)

        assertThat(streams.hlsUrl).isEqualTo("https://example.invalid/live.m3u8")
        assertThat(streams.dashManifestUrl).isEqualTo("https://example.invalid/live.mpd")
        assertThat(streams.subtitles).isEmpty()
        assertThat(streams.identity.title).isEqualTo("Live")
        assertThat(streams.identity.enrichedVideo.duration).isEqualTo(0)
    }

    @Test
    fun `a live stream is seekable unless its creator turned DVR off`() {
        val base = playerResponse(title = "t", author = "a", channelId = "c", lengthSeconds = "0", thumbnailUrl = null)
        val noDvr = base.copy(videoDetails = base.videoDetails!!.copy(isLive = true, isLiveDvrEnabled = false))

        assertThat(preparer.assembleLive(VIDEO_ID, null, extraction(playerResponse = base, isLive = true)).isDvrEnabled).isTrue()
        assertThat(preparer.assembleLive(VIDEO_ID, null, extraction(playerResponse = noDvr, isLive = true)).isDvrEnabled).isFalse()
    }

    @Test
    fun `a live extraction still prefers the cached title over the Live placeholder`() {
        val result =
            extraction(
                playerResponse = playerResponse(title = null, author = null, channelId = "", lengthSeconds = "600", thumbnailUrl = null),
                isLive = true,
            )

        val identity = preparer.assembleLive(VIDEO_ID, cachedVideo(), result).identity

        assertThat(identity.title).isEqualTo("Cached title")
        assertThat(identity.enrichedVideo.duration).isEqualTo(0)
    }

    @Test
    fun `creator keywords become engine topic tags`() {
        val identity =
            preparer.assembleVod(VIDEO_ID, cached = null, step = vodStep(keywords = listOf("storage", "backup"))).identity

        assertThat(identity.enrichedVideo.tags).containsExactly("storage", "backup").inOrder()
    }

    @Test
    fun `the category is kept apart from the creator's keywords`() {
        val identity =
            preparer
                .assembleVod(
                    VIDEO_ID,
                    cached = null,
                    step = vodStep(keywords = listOf("storage"), category = "Science & Technology"),
                ).identity

        assertThat(identity.enrichedVideo.tags).containsExactly("storage")
        assertThat(identity.enrichedVideo.category).isEqualTo("Science & Technology")
    }

    @Test
    fun `a response without a microformat keeps the category already known`() {
        val identity = preparer.assembleVod(VIDEO_ID, cached = null, step = vodStep(keywords = listOf("storage"))).identity
        val known = preparer.assembleVod(VIDEO_ID, cachedVideo().copy(category = "Music"), vodStep(keywords = listOf("storage"))).identity

        assertThat(identity.enrichedVideo.tags).containsExactly("storage")
        assertThat(identity.enrichedVideo.category).isNull()
        assertThat(known.enrichedVideo.category).isEqualTo("Music")
    }

    @Test
    fun `cached tags are kept when the response carries none`() {
        val cached = cachedVideo().copy(tags = listOf("remembered"))

        val identity = preparer.assembleVod(VIDEO_ID, cached, vodStep()).identity

        assertThat(identity.enrichedVideo.tags).containsExactly("remembered")
    }

    @Test
    fun `a creator who hides likes turns the like count off for VOD and live`() {
        val step = vodStep()
        val hidden = step.result.playerResponse.let { it.copy(videoDetails = it.videoDetails!!.copy(allowRatings = false)) }

        assertThat(preparer.assembleVod(VIDEO_ID, null, step).ratingsAllowed).isTrue()
        assertThat(preparer.assembleVod(VIDEO_ID, null, step.copy(result = extraction(hidden))).ratingsAllowed).isFalse()
        assertThat(preparer.assembleLive(VIDEO_ID, null, extraction(hidden, isLive = true)).ratingsAllowed).isFalse()
    }

    @Test
    fun `an ended stream's real start becomes its exact date`() {
        val base = vodStep().result.playerResponse
        val streamed =
            base.copy(
                videoDetails = base.videoDetails!!.copy(isLiveContent = true, isLive = false),
                microformat =
                    PlayerResponse.Microformat(
                        PlayerResponse.Microformat.PlayerMicroformatRenderer(
                            liveBroadcastDetails =
                                PlayerResponse.Microformat.PlayerMicroformatRenderer.LiveBroadcastDetails(
                                    isLiveNow = false,
                                    startTimestamp = "2026-09-15T23:00:27+00:00",
                                    endTimestamp = "2026-09-16T01:12:03+00:00",
                                ),
                        ),
                    ),
            )

        val streams = preparer.assembleVod(VIDEO_ID, cachedVideo(), vodStep().copy(result = extraction(streamed)))

        assertThat(streams.isArchivedLivestream).isTrue()
        assertThat(streams.identity.enrichedVideo.timestamp).isEqualTo(1_789_513_227_000L)
        assertThat(streams.identity.enrichedVideo.timestampIsExact).isTrue()
    }

    @Test
    fun `without broadcast details the date stays as it was`() {
        val streams = preparer.assembleVod(VIDEO_ID, cachedVideo(), vodStep())

        assertThat(streams.isArchivedLivestream).isFalse()
        assertThat(streams.identity.enrichedVideo.timestampIsExact).isFalse()
    }

    private fun cachedVideo(): Video =
        Video(
            id = VIDEO_ID,
            title = "Cached title",
            channelName = "Cached channel",
            channelId = "channel_cached",
            thumbnailUrl = "https://example.invalid/cached.jpg",
            duration = 120,
            viewCount = 1L,
            uploadDate = "2026-01-01",
        )

    private fun vodStep(
        preferredQuality: VideoQuality = VideoQuality.Q_1080P,
        title: String? = "InnerTube title",
        author: String? = "InnerTube channel",
        channelId: String = "UC_innertube",
        lengthSeconds: String = "300",
        thumbnailUrl: String? = "https://example.invalid/innertube.jpg",
        keywords: List<String>? = null,
        category: String? = null,
        description: String? = null,
    ): ResolvedPlayback.VodFromInnerTube =
        ResolvedPlayback.VodFromInnerTube(
            result =
                extraction(
                    playerResponse(title, author, channelId, lengthSeconds, thumbnailUrl, keywords, category, description),
                ),
            relatedVideos = emptyList(),
            preferredQuality = preferredQuality,
            preferredAudioLanguage = "original",
            preferredCodecKey = "auto",
            preferredSubtitleLanguage = CaptionTrackResolver.NO_PREFERRED_LANGUAGE,
            resumePositionOverrideMs = null,
        )

    private fun extraction(
        playerResponse: PlayerResponse,
        isLive: Boolean = false,
        liveHlsUrl: String? = null,
        liveDashUrl: String? = null,
    ): InnerTubeVideoStreamExtractor.VideoExtractionResult =
        InnerTubeVideoStreamExtractor.VideoExtractionResult(
            videoFormats = fakeVideoFormats(),
            audioFormats = fakeAudioFormats(),
            playerResponse = playerResponse,
            usedClient = YouTubeClient.WEB,
            sabrInfo = null,
            isLive = isLive,
            liveHlsUrl = liveHlsUrl,
            liveDashUrl = liveDashUrl,
        )

    private fun playerResponse(
        title: String?,
        author: String?,
        channelId: String,
        lengthSeconds: String,
        thumbnailUrl: String?,
        keywords: List<String>? = null,
        category: String? = null,
        description: String? = null,
    ): PlayerResponse =
        PlayerResponse(
            responseContext = ResponseContext(visitorData = null, serviceTrackingParams = null),
            playabilityStatus = PlayerResponse.PlayabilityStatus(status = "OK", reason = null),
            playerConfig = null,
            streamingData = null,
            videoDetails =
                PlayerResponse.VideoDetails(
                    videoId = VIDEO_ID,
                    title = title,
                    author = author,
                    channelId = channelId,
                    shortDescription = description,
                    lengthSeconds = lengthSeconds,
                    thumbnail = thumbnailUrl?.let { Thumbnails(listOf(Thumbnail(url = it, width = 1280, height = 720))) },
                    keywords = keywords,
                ),
            playbackTracking = null,
            microformat =
                category?.let {
                    PlayerResponse.Microformat(
                        playerMicroformatRenderer = PlayerResponse.Microformat.PlayerMicroformatRenderer(category = it),
                    )
                },
        )

    private companion object {
        const val VIDEO_ID = "vid_preparer"
    }
}
