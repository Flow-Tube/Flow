package io.github.aedev.flow.ui.screens.player.state

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.player.stream.UpcomingDetails
import io.github.aedev.flow.player.stream.UpcomingPremiere
import org.junit.Test

class UpcomingPremierePolicyTest {
    private val now = 1_800_000_000_000L

    private fun video(
        id: String = "vid",
        isUpcoming: Boolean = true,
        timestamp: Long = 0L,
        uploadDate: String = "",
    ): Video =
        Video(
            id = id,
            title = "Title",
            channelName = "Channel",
            channelId = "channel",
            thumbnailUrl = "",
            duration = 0,
            viewCount = 0L,
            uploadDate = uploadDate,
            isUpcoming = isUpcoming,
            timestamp = timestamp,
        )

    @Test
    fun `the probe fills what the list row and a deep link never carry`() {
        val details =
            UpcomingDetails(
                title = "Probed title",
                channelName = "Probed channel",
                channelId = "UCprobed",
                thumbnailUrl = "https://i.ytimg.test/probed.jpg",
                description = "Probed description",
            )

        val fromRow = UpcomingPremierePolicy.upcomingVideo("vid", video(), releaseMs = now, details = details)
        assertThat(fromRow.title).isEqualTo("Title")
        assertThat(fromRow.channelName).isEqualTo("Channel")
        assertThat(fromRow.description).isEqualTo("Probed description")
        assertThat(fromRow.thumbnailUrl).isEqualTo("https://i.ytimg.test/probed.jpg")

        val fromLink = UpcomingPremierePolicy.upcomingVideo("vid", cached = null, releaseMs = now, details = details)
        assertThat(fromLink.title).isEqualTo("Probed title")
        assertThat(fromLink.channelId).isEqualTo("UCprobed")
        assertThat(fromLink.isUpcoming).isTrue()
        assertThat(fromLink.timestamp).isEqualTo(now)
    }

    @Test
    fun `a probed scheduled stream is not shown as a premiere`() {
        val details =
            UpcomingDetails(
                title = "Launch",
                channelName = "Agency",
                channelId = "UCagency",
                thumbnailUrl = "",
                description = "",
                isScheduledLive = true,
            )

        assertThat(UpcomingPremierePolicy.upcomingVideo("vid", cached = null, releaseMs = now, details = details).isScheduledLive).isTrue()
        assertThat(UpcomingPremierePolicy.upcomingVideo("vid", cached = null, releaseMs = now, details = null).isScheduledLive).isFalse()
    }

    @Test
    fun `a video that is not upcoming has no release time`() {
        assertThat(
            UpcomingPremierePolicy.releaseTimeFor(video(isUpcoming = false, timestamp = now + 3_600_000L), now),
        ).isNull()
    }

    @Test
    fun `a feed timestamp comfortably ahead of now is the release time`() {
        val releaseMs = now + 3_600_000L

        assertThat(UpcomingPremierePolicy.releaseTimeFor(video(timestamp = releaseMs), now)).isEqualTo(releaseMs)
    }

    @Test
    fun `a release time in the past is not counted down to`() {
        assertThat(UpcomingPremierePolicy.releaseTimeFor(video(timestamp = now - 3_600_000L), now)).isNull()
        assertThat(UpcomingPremierePolicy.releaseTimeFor(video(timestamp = now), now)).isNull()
    }

    @Test
    fun `a timestamp too close to now is treated as an upload date, not a premiere time`() {
        // Inside the lead window the field is indistinguishable from an upload stamp, so the label
        // is parsed instead - and there is no label here.
        assertThat(UpcomingPremierePolicy.releaseTimeFor(video(timestamp = now + 30_000L), now)).isNull()
    }

    @Test
    fun `a probe is only needed when the metadata does not already answer both questions`() {
        assertThat(UpcomingPremierePolicy.needsProbe(flagged = true, listReleaseMs = now)).isFalse()
        assertThat(UpcomingPremierePolicy.needsProbe(flagged = true, listReleaseMs = null)).isTrue()
        assertThat(UpcomingPremierePolicy.needsProbe(flagged = false, listReleaseMs = now)).isTrue()
        assertThat(UpcomingPremierePolicy.needsProbe(flagged = false, listReleaseMs = null)).isTrue()
    }

    @Test
    fun `an unflagged video the probe does not recognise is not upcoming`() {
        assertThat(
            UpcomingPremierePolicy.resolve(flagged = false, listReleaseMs = null, probe = UpcomingPremiere.NOT_UPCOMING),
        ).isEqualTo(UpcomingPremiere.NOT_UPCOMING)
    }

    @Test
    fun `a flagged video stays upcoming even when the probe finds nothing`() {
        assertThat(
            UpcomingPremierePolicy.resolve(flagged = true, listReleaseMs = null, probe = UpcomingPremiere.NOT_UPCOMING),
        ).isEqualTo(UpcomingPremiere(isUpcoming = true, scheduledStartMs = null))
    }

    @Test
    fun `the list release time wins over the probed one`() {
        val probe = UpcomingPremiere(isUpcoming = true, scheduledStartMs = now + 7_200_000L)

        assertThat(UpcomingPremierePolicy.resolve(flagged = true, listReleaseMs = now + 60_000L, probe = probe))
            .isEqualTo(UpcomingPremiere(isUpcoming = true, scheduledStartMs = now + 60_000L))
        assertThat(UpcomingPremierePolicy.resolve(flagged = false, listReleaseMs = null, probe = probe))
            .isEqualTo(probe)
    }

    @Test
    fun `applying the countdown clears the previous video and keeps the queue title`() {
        val previous =
            VideoPlayerUiState(
                cachedVideo = video(id = "old", isUpcoming = false).copy(channelId = "old_channel"),
                relatedVideos = listOf(video(id = "r1", isUpcoming = false)),
                hlsUrl = "https://example.invalid/manifest.m3u8",
                localFilePath = "/sdcard/old.mp4",
                isSubscribed = true,
                isLoading = true,
            )

        val state = UpcomingPremierePolicy.applyTo(previous, video(id = "new"), now + 60_000L, preserveQueueTitle = "Mix")

        assertThat(state.cachedVideo?.id).isEqualTo("new")
        assertThat(state.isUpcoming).isTrue()
        assertThat(state.upcomingReleaseTimeMs).isEqualTo(now + 60_000L)
        assertThat(state.isLoading).isFalse()
        assertThat(state.queueTitle).isEqualTo("Mix")
        assertThat(state.relatedVideos).isEmpty()
        assertThat(state.hlsUrl).isNull()
        assertThat(state.localFilePath).isNull()
        assertThat(state.isSubscribed).isFalse()
    }

    @Test
    fun `entering the countdown after a load keeps what the load already gathered`() {
        val loaded =
            VideoPlayerUiState(
                cachedVideo = video(id = "vid", isUpcoming = false),
                relatedVideos = listOf(video(id = "r1", isUpcoming = false)),
                isSubscribed = true,
                likeState = "liked",
                isLive = true,
                isLoading = true,
            )

        val state = UpcomingPremierePolicy.enterFrom(loaded, video(id = "vid"), relatedVideos = emptyList(), releaseMs = null)

        assertThat(state.relatedVideos.map { it.id }).containsExactly("r1")
        assertThat(state.isSubscribed).isTrue()
        assertThat(state.likeState).isEqualTo("liked")
        assertThat(state.isLive).isFalse()
        assertThat(state.isUpcoming).isTrue()
        assertThat(state.isLoading).isFalse()
    }

    @Test
    fun `the countdown video carries the resolved release time as its timestamp`() {
        val resolved = UpcomingPremierePolicy.upcomingVideo("vid", cached = null, releaseMs = now + 60_000L)

        assertThat(resolved.id).isEqualTo("vid")
        assertThat(resolved.isUpcoming).isTrue()
        assertThat(resolved.timestamp).isEqualTo(now + 60_000L)

        val fromCache = UpcomingPremierePolicy.upcomingVideo("vid", cached = video(timestamp = 42L), releaseMs = null)
        assertThat(fromCache.timestamp).isEqualTo(42L)
        assertThat(fromCache.title).isEqualTo("Title")
    }
}
