package io.github.aedev.flow.ui.screens.player.state

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.SponsorBlockSegment
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.innertube.models.response.VideoChapter
import io.github.aedev.flow.innertube.models.response.VideoHeatmap
import io.github.aedev.flow.player.state.EnhancedPlayerState
import io.github.aedev.flow.player.stream.StoryboardLevel
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Test

/**
 * Pins the decisions a load makes before it does anything: whether it runs at all, what the screen
 * looks like while it does, and what a late prepare can arm from the screen state alone.
 */
class PlayerLoadDecisionsTest {
    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `a forced refresh always runs`() {
        val loaded = VideoPlayerUiState(cachedVideo = video("vid_a"))

        assertThat(loaded.loadSkipReason("vid_a", forceRefresh = true)).isNull()
        assertThat(loaded.loadSkipReason("vid_a", forceRefresh = false)).isEqualTo(LoadSkip.ALREADY_LOADED)
    }

    @Test
    fun `a video already loaded with an error is not skipped`() {
        val failed = VideoPlayerUiState(cachedVideo = video("vid_a"), error = "boom")

        assertThat(failed.loadSkipReason("vid_a", forceRefresh = false)).isNull()
    }

    @Test
    fun `a load in flight for the same video is skipped`() {
        val byStream = VideoPlayerUiState(isLoading = true, cachedVideo = video("vid_a"))
        val byCache = VideoPlayerUiState(isLoading = true, cachedVideo = video("vid_a"))
        val other = VideoPlayerUiState(isLoading = true, cachedVideo = video("vid_b"))

        assertThat(byStream.loadSkipReason("vid_a", forceRefresh = false)).isEqualTo(LoadSkip.ALREADY_LOADING)
        assertThat(byCache.loadSkipReason("vid_a", forceRefresh = false)).isEqualTo(LoadSkip.ALREADY_LOADING)
        assertThat(other.loadSkipReason("vid_a", forceRefresh = false)).isNull()
    }

    @Test
    fun `beginning a load clears the previous video and keeps the avatar and engagement this one came with`() {
        val before =
            VideoPlayerUiState(
                cachedVideo = video("vid_a").copy(channelThumbnailUrl = "avatar.jpg"),
                relatedVideos = listOf(video("rel_1")),
                streamSizes = mapOf("137" to 1L),
                error = "boom",
                isSubscribed = true,
                likeState = "LIKED",
                dislikeCount = 5L,
                hlsUrl = "https://example.invalid/live.m3u8",
                isLive = true,
                isUpcoming = true,
                localFilePath = "/tmp/a.mp4",
            )

        val next = before.beginLoadFor("vid_a")

        assertThat(next.isLoading).isTrue()
        assertThat(next.error).isNull()
        assertThat(next.relatedVideos).isEmpty()
        assertThat(next.streamSizes).isEmpty()
        assertThat(next.isSubscribed).isTrue()
        assertThat(next.likeState).isEqualTo("LIKED")
        assertThat(next.dislikeCount).isNull()
        assertThat(next.hlsUrl).isNull()
        assertThat(next.isLive).isFalse()
        assertThat(next.isUpcoming).isFalse()
        assertThat(next.localFilePath).isNull()
        assertThat(next.channelAvatarUrl).isEqualTo("avatar.jpg")
        assertThat(next.cachedVideo).isEqualTo(before.cachedVideo)
    }

    @Test
    fun `beginning a load for a different video keeps no avatar at all`() {
        val before = VideoPlayerUiState(cachedVideo = video("vid_a").copy(channelThumbnailUrl = "avatar.jpg"))

        assertThat(before.beginLoadFor("vid_b").channelAvatarUrl).isNull()
    }

    @Test
    fun `beginning a load for a different video drops the engagement of the one before`() {
        val before = VideoPlayerUiState(cachedVideo = video("vid_a"), isSubscribed = true, likeState = "LIKED")

        val next = before.beginLoadFor("vid_b")

        assertThat(next.isSubscribed).isFalse()
        assertThat(next.likeState).isNull()
    }

    @Test
    fun `the next video from the same channel stays subscribed but not liked`() {
        val before = VideoPlayerUiState(cachedVideo = video("vid_a"), isSubscribed = true, likeState = "LIKED")

        val next = before.resetForVideo(video("vid_b").copy(channelId = "channel_vid_a"))

        assertThat(next.isSubscribed).isTrue()
        assertThat(next.likeState).isNull()
    }

    @Test
    fun `the next video from another or an unknown channel starts unsubscribed`() {
        val before = VideoPlayerUiState(cachedVideo = video("vid_a").copy(channelId = ""), isSubscribed = true)

        assertThat(before.resetForVideo(video("vid_b")).isSubscribed).isFalse()
        assertThat(before.resetForVideo(video("vid_b").copy(channelId = "")).isSubscribed).isFalse()
    }

    @Test
    fun `replaying the video on screen keeps its subscription and like`() {
        val before = VideoPlayerUiState(cachedVideo = video("vid_a"), isSubscribed = true, likeState = "LIKED")

        val next = before.resetForVideo(video("vid_a"))

        assertThat(next.isSubscribed).isTrue()
        assertThat(next.likeState).isEqualTo("LIKED")
    }

    @Test
    fun `a known premiere short-circuits to the countdown`() {
        val next = VideoPlayerUiState(isLoading = true, localFilePath = "/tmp/a.mp4").applyCachedUpcoming(1_700L)

        assertThat(next.isUpcoming).isTrue()
        assertThat(next.upcomingReleaseTimeMs).isEqualTo(1_700L)
        assertThat(next.isLoading).isFalse()
        assertThat(next.localFilePath).isNull()
        assertThat(next.localFileVideoId).isNull()
    }

    @Test
    fun `the next video starts without the chapters, curve or filmstrip of the one before`() {
        val before =
            VideoPlayerUiState(
                chapters = listOf(VideoChapter("Intro", 0)),
                heatmap = VideoHeatmap(markers = listOf(mockk()), highlights = emptyList()),
                storyboard = listOf(mockk<StoryboardLevel>()),
            )

        val next = before.resetForVideo(video("vid_b"))

        assertThat(next.chapters).isEmpty()
        assertThat(next.heatmap).isNull()
        assertThat(next.storyboard).isEmpty()
    }

    @Test
    fun `starting a play opens the sheet on the new video's own metadata`() {
        val video = video("vid_a").copy(channelThumbnailUrl = "avatar.jpg")
        val before = VideoPlayerUiState(isBackgroundPlaybackMode = true, shouldDismissPlayer = true, channelSubscriberCount = 9L)

        val next = before.startPlaybackOf(video)

        assertThat(next.cachedVideo).isEqualTo(video)
        assertThat(next.isLoading).isTrue()
        assertThat(next.isBackgroundPlaybackMode).isFalse()
        assertThat(next.shouldDismissPlayer).isFalse()
        assertThat(next.channelAvatarUrl).isEqualTo("avatar.jpg")
        assertThat(next.channelSubscriberCount).isNull()
    }

    @Test
    fun `starting a local play loads nothing and stamps the content uri`() {
        val video = video("local_1")

        val next = VideoPlayerUiState().startLocalPlaybackOf(video, "content://media/1")

        assertThat(next.isLoading).isFalse()
        assertThat(next.localFilePath).isEqualTo("content://media/1")
        assertThat(next.localFileVideoId).isEqualTo("local_1")
        assertThat(next.offlineSponsorBlockSegments).isNull()
    }

    @Test
    fun `clearing the player keeps only the autoplay and adaptive settings`() {
        val before = VideoPlayerUiState(cachedVideo = video("vid_a"), autoplayEnabled = false, isAdaptiveMode = true, error = "boom")

        assertThat(before.clearedForNoVideo()).isEqualTo(VideoPlayerUiState(autoplayEnabled = false, isAdaptiveMode = true))
    }

    @Test
    fun `a reusable playback of the same video reopens the sheet instead of playing again`() {
        val background = VideoPlayerUiState(isBackgroundPlaybackMode = true)
        val playing = EnhancedPlayerState(currentVideoId = "vid_a", isPrepared = true, isPlaying = true)

        // Either the background flag or a collapsed mini player is enough.
        assertThat(background.shouldReopenInsteadOfPlaying("vid_a", playing, isMiniPlayerCollapsed = false)).isTrue()
        assertThat(VideoPlayerUiState().shouldReopenInsteadOfPlaying("vid_a", playing, isMiniPlayerCollapsed = true)).isTrue()
        assertThat(VideoPlayerUiState().shouldReopenInsteadOfPlaying("vid_a", playing, isMiniPlayerCollapsed = false)).isFalse()

        // A different video, or a player with nothing to reuse, always plays.
        assertThat(background.shouldReopenInsteadOfPlaying("vid_b", playing, isMiniPlayerCollapsed = true)).isFalse()
        assertThat(
            background.shouldReopenInsteadOfPlaying("vid_a", EnhancedPlayerState(currentVideoId = "vid_a"), isMiniPlayerCollapsed = true),
        ).isFalse()
    }

    @Test
    fun `a local copy with no stream metadata is what a late prepare arms`() {
        val state =
            VideoPlayerUiState(
                localFilePath = "/tmp/a.mp4",
                localFileVideoId = "vid_a",
                savedPosition = 4_000L,
                offlineSponsorBlockSegments = listOf(segment()),
            )

        val prepare = state.latePrepare("vid_a")

        assertThat(prepare).isInstanceOf(LatePrepare.LocalFile::class.java)
        val local = prepare as LatePrepare.LocalFile
        assertThat(local.localFilePath).isEqualTo("/tmp/a.mp4")
        assertThat(local.savedPosition).isEqualTo(4_000L)
        assertThat(local.offlineSegments).hasSize(1)
    }

    @Test
    fun `a local copy stamped for another video is not armed`() {
        val state = VideoPlayerUiState(localFilePath = "/tmp/a.mp4", localFileVideoId = "vid_b")

        assertThat(state.latePrepare("vid_a")).isNull()
    }

    @Test
    fun `a loading, failed or restored screen arms nothing and only the video it holds is armed`() {
        assertThat(VideoPlayerUiState(isLoading = true).blocksLatePrepare()).isTrue()
        assertThat(VideoPlayerUiState(error = "boom").blocksLatePrepare()).isTrue()
        assertThat(VideoPlayerUiState(isRestoredSession = true).blocksLatePrepare()).isTrue()
        assertThat(VideoPlayerUiState().blocksLatePrepare()).isFalse()

        assertThat(VideoPlayerUiState(cachedVideo = video("vid_a")).holdsVideo("vid_a")).isTrue()
        assertThat(VideoPlayerUiState(localFileVideoId = "vid_a").holdsVideo("vid_a")).isTrue()
        assertThat(VideoPlayerUiState().holdsVideo("vid_a")).isFalse()
    }

    @Test
    fun `a link to the video already playing only reopens the sheet`() {
        val playing = VideoPlayerUiState(cachedVideo = video("vid_a"))
        val loading = VideoPlayerUiState(cachedVideo = video("vid_a"), isLoading = true)

        assertThat(playing.shouldExpandInsteadOfPlaying("vid_a", playerVideoId = "vid_a")).isTrue()
        assertThat(loading.shouldExpandInsteadOfPlaying("vid_a", playerVideoId = null)).isTrue()
    }

    @Test
    fun `a link to a video the player let go plays it again`() {
        val handedAway = VideoPlayerUiState(cachedVideo = video("vid_a"))

        assertThat(handedAway.shouldExpandInsteadOfPlaying("vid_a", playerVideoId = null)).isFalse()
        assertThat(handedAway.shouldExpandInsteadOfPlaying("vid_a", playerVideoId = "vid_b")).isFalse()
    }

    @Test
    fun `a link to a failed, restored or upcoming video plays it again`() {
        val failed = VideoPlayerUiState(cachedVideo = video("vid_a"), error = "boom")
        val restored = VideoPlayerUiState(cachedVideo = video("vid_a"), isRestoredSession = true)
        val upcoming = VideoPlayerUiState(cachedVideo = video("vid_a"), isUpcoming = true)

        assertThat(failed.shouldExpandInsteadOfPlaying("vid_a", playerVideoId = "vid_a")).isFalse()
        assertThat(restored.shouldExpandInsteadOfPlaying("vid_a", playerVideoId = "vid_a")).isFalse()
        assertThat(upcoming.shouldExpandInsteadOfPlaying("vid_a", playerVideoId = "vid_a")).isFalse()
    }

    @Test
    fun `a link to another video or an empty screen plays it`() {
        assertThat(
            VideoPlayerUiState(cachedVideo = video("vid_b")).shouldExpandInsteadOfPlaying("vid_a", playerVideoId = "vid_b"),
        ).isFalse()
        assertThat(VideoPlayerUiState().shouldExpandInsteadOfPlaying("vid_a", playerVideoId = null)).isFalse()
    }

    @Test
    fun `a downloaded video reads its watch page only when online`() {
        assertThat(shouldLoadOnlineMetadataForLocalCopy("vid_a", isOnline = true)).isTrue()
        assertThat(shouldLoadOnlineMetadataForLocalCopy("vid_a", isOnline = false)).isFalse()
    }

    @Test
    fun `a device file never reads a watch page`() {
        assertThat(shouldLoadOnlineMetadataForLocalCopy("local_42", isOnline = true)).isFalse()
    }

    private fun video(id: String): Video =
        Video(
            id = id,
            title = "Title $id",
            channelName = "Channel $id",
            channelId = "channel_$id",
            thumbnailUrl = "https://example.invalid/$id.jpg",
            duration = 120,
            viewCount = 1L,
            uploadDate = "2026-01-01",
        )

    private fun segment(): SponsorBlockSegment =
        SponsorBlockSegment(category = "sponsor", segment = listOf(0f, 1f), uuid = "uuid_1", actionType = "skip")
}
