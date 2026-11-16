package io.github.aedev.flow.ui.screens.player

import android.content.Context
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.notification.UpcomingReminder
import io.github.aedev.flow.notification.UpcomingVideoReminderWorker
import io.github.aedev.flow.player.GlobalPlayerState
import io.github.aedev.flow.player.error.PlayerDiagnostics
import io.github.aedev.flow.player.stream.UpcomingDetails
import io.github.aedev.flow.player.stream.UpcomingPremiere
import io.github.aedev.flow.player.stream.UpcomingPremiereProbe
import io.github.aedev.flow.ui.screens.player.state.UpcomingPremierePolicy
import io.github.aedev.flow.ui.screens.player.state.VideoPlayerUiState
import io.github.aedev.flow.ui.screens.player.state.applyCachedUpcoming
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Everything the player screen does with a video that has not started yet: deciding whether it is a
 * premiere, showing the countdown instead of playback, and the reminder the user can arm on it.
 *
 * The decisions themselves live in [UpcomingPremierePolicy]; this class owns the network probe, the
 * countdown's place in a load, and the reminder work. It writes the same state flow the ViewModel
 * constructs, and asks it whether a load is still current before any countdown replaces the screen.
 *
 * The probe is entered once per resolution, from the one lookup the load resolver calls back into.
 */
internal class UpcomingPremiereController(
    private val context: Context,
    private val uiState: MutableStateFlow<VideoPlayerUiState>,
    private val playerPreferences: PlayerPreferences,
    private val probe: UpcomingPremiereProbe,
    private val scope: CoroutineScope,
    private val isLoadCurrent: (Long) -> Boolean,
    private val armMetadata: (videoId: String) -> Unit,
) {
    /** Mirrors the stored reminder ids onto the video the screen currently holds. */
    fun collectReminderState() {
        combine(
            playerPreferences.upcomingVideoReminderIds,
            uiState.map { it.cachedVideo?.id }.distinctUntilChanged(),
        ) { reminderIds, videoId ->
            videoId != null && videoId in reminderIds
        }.onEach { isReminderSet ->
            uiState.update { it.copy(isUpcomingReminderSet = isReminderSet) }
        }.launchIn(scope)
    }

    /** The countdown for a video whose own metadata already announces a release still ahead. */
    fun applyCountdown(
        video: Video,
        preserveQueueTitle: String? = uiState.value.queueTitle,
    ): Boolean {
        val releaseTimeMs = UpcomingPremierePolicy.releaseTimeFor(video) ?: return false
        uiState.update { UpcomingPremierePolicy.applyTo(it, video, releaseTimeMs, preserveQueueTitle) }
        armMetadata(video.id)
        return true
    }

    /** The countdown a load can skip straight to, because the screen already holds the premiere. */
    fun applyCachedCountdown(videoId: String): Boolean {
        val releaseTimeMs =
            uiState.value.cachedVideo
                ?.takeIf { it.id == videoId && it.isUpcoming }
                ?.let(UpcomingPremierePolicy::releaseTimeFor) ?: return false
        uiState.update { it.applyCachedUpcoming(releaseTimeMs) }
        armMetadata(videoId)
        return true
    }

    /** The countdown a finished load lands on, keeping what that load already gathered. */
    fun enterCountdown(
        videoId: String,
        releaseMs: Long?,
        relatedVideos: List<Video>,
        loadToken: Long,
        details: UpcomingDetails? = null,
    ): Boolean {
        if (!isLoadCurrent(loadToken)) return true
        val cached = uiState.value.cachedVideo?.takeIf { it.id == videoId }
        val upcomingVideo = UpcomingPremierePolicy.upcomingVideo(videoId, cached, releaseMs, details)
        uiState.update { UpcomingPremierePolicy.enterFrom(it, upcomingVideo, relatedVideos, releaseMs) }
        GlobalPlayerState.setCurrentVideo(upcomingVideo)
        return true
    }

    /** Reports whether [videoId] turned out to be a premiere, entering the countdown when it is. */
    suspend fun tryEnterCountdown(
        videoId: String,
        relatedVideos: List<Video>,
        loadToken: Long,
    ): Boolean {
        val resolved = resolve(videoId, knownUpcoming = false)
        if (!resolved.isUpcoming) return false
        return enterCountdown(videoId, resolved.scheduledStartMs, relatedVideos, loadToken, resolved.details)
    }

    suspend fun resolve(
        videoId: String,
        knownUpcoming: Boolean,
    ): UpcomingPremiere {
        val cached = uiState.value.cachedVideo?.takeIf { it.id == videoId }
        val flagged = knownUpcoming || cached?.isUpcoming == true
        val listReleaseMs = cached?.let { UpcomingPremierePolicy.releaseTimeFor(it) }
        if (!UpcomingPremierePolicy.needsProbe(flagged, listReleaseMs)) {
            return UpcomingPremiere(isUpcoming = true, scheduledStartMs = listReleaseMs)
        }
        val probed = probe.probe(videoId)
        PlayerDiagnostics.logWarning(
            "Upcoming",
            "videoId=$videoId flagged=$flagged known=$knownUpcoming " +
                "probe=${probed.isUpcoming} probeTime=${probed.scheduledStartMs}",
        )
        return UpcomingPremierePolicy.resolve(flagged, listReleaseMs, probed)
    }

    fun toggleReminder() {
        val state = uiState.value
        val video = state.cachedVideo ?: return
        val releaseTimeMs = state.upcomingReleaseTimeMs ?: UpcomingPremierePolicy.releaseTimeFor(video) ?: return
        if (!state.isUpcoming) return

        scope.launch {
            val enableReminder = !state.isUpcomingReminderSet
            playerPreferences.setUpcomingVideoReminder(video.id, enableReminder)
            if (enableReminder) {
                playerPreferences.saveUpcomingReminder(
                    UpcomingReminder(video.id, releaseTimeMs, video.title, video.channelName, video.thumbnailUrl),
                )
                UpcomingVideoReminderWorker.scheduleReminder(
                    context = context,
                    videoId = video.id,
                    releaseTimeMs = releaseTimeMs,
                    title = video.title,
                    channelName = video.channelName,
                    thumbnailUrl = video.thumbnailUrl,
                )
            } else {
                UpcomingVideoReminderWorker.cancelReminder(context, video.id)
            }
            uiState.update { it.copy(isUpcomingReminderSet = enableReminder) }
        }
    }
}
