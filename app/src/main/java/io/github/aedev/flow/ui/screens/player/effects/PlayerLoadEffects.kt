package io.github.aedev.flow.ui.screens.player.effects

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.state.SubtitleLoadFailure
import io.github.aedev.flow.player.state.SubtitleOption
import io.github.aedev.flow.ui.screens.player.VideoPlayerViewModel
import io.github.aedev.flow.ui.screens.player.state.PlayerScreenState
import io.github.aedev.flow.ui.screens.player.state.SubtitleSelection
import io.github.aedev.flow.ui.screens.player.state.VideoPlayerUiState
import io.github.aedev.flow.utils.NetworkState
import io.github.aedev.flow.utils.sponsorCategoryLabelRes
import kotlinx.coroutines.delay

@Composable
internal fun VideoLoadEffect(
    videoId: String,
    context: Context,
    screenState: PlayerScreenState,
    viewModel: VideoPlayerViewModel,
) {
    LaunchedEffect(videoId) {
        screenState.resetForNewVideo()

        viewModel.loadVideoInfo(videoId, NetworkState.isOnWifi(context))
    }
}

/**
 * The load pipeline a genuinely new video needs. A restored session already has a prepared
 * player, so the host composes none of this for one.
 */
@Composable
internal fun PlayerFreshSessionEffects(
    videoId: String,
    context: Context,
    screenState: PlayerScreenState,
    uiState: VideoPlayerUiState,
    viewModel: VideoPlayerViewModel,
) {
    VideoLoadEffect(
        videoId = videoId,
        context = context,
        screenState = screenState,
        viewModel = viewModel,
    )

    LaunchedEffect(
        videoId,
        uiState.isLoading,
        uiState.error,
        uiState.audioStream,
        uiState.localFilePath,
    ) {
        viewModel.ensurePlaybackPrepared(videoId)
    }

    PlaybackStartupRecoveryEffect(
        videoId = videoId,
        uiState = uiState,
        screenState = screenState,
        viewModel = viewModel,
    )
}

/**
 * Adopts whatever the player singleton is actually playing when it changes underneath the UI.
 *
 * The comments fetch is deliberately not part of that adoption. Only the expanded body renders
 * comments — the preview under the video and the comments sheet — so while the player is collapsed
 * to the mini bar a queue advance used to spend a network round trip on a surface nobody can see.
 * [expandedBodyVisible] defers it, and the per-id latch is what makes expanding later fetch the
 * comments the collapsed advances skipped without re-fetching the ones already held.
 */
@Composable
internal fun GlobalVideoSyncEffect(
    currentVideoId: String?,
    currentVideo: () -> Video?,
    uiState: VideoPlayerUiState,
    commentsEnabled: Boolean,
    expandedBodyVisible: Boolean,
    viewModel: VideoPlayerViewModel,
) {
    LaunchedEffect(currentVideoId) {
        val current = currentVideo()
        // Unconditional: the second half of the old guard compared an extractor id that was
        // always absent, so this always fired, and the view model is the one that decides whether
        // a sync is a no-op.
        if (current != null && !uiState.isRestoredSession) {
            viewModel.syncWithCurrentPlayerVideo(current)
        }
    }

    var commentsLoadedFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(currentVideoId, expandedBodyVisible) {
        if (!commentsEnabled || !expandedBodyVisible) return@LaunchedEffect
        val current = currentVideo() ?: return@LaunchedEffect
        if (uiState.isRestoredSession || commentsLoadedFor == current.id) return@LaunchedEffect
        commentsLoadedFor = current.id
        viewModel.loadComments(current.id)
    }
}

@Composable
internal fun ShortVideoPromptEffect(
    videoDuration: Int,
    screenState: PlayerScreenState,
    isInQueue: Boolean,
    disableShortsPlayer: Boolean,
    showShortsPlayerPrompt: Boolean,
) {
    LaunchedEffect(videoDuration, screenState.hasShownShortsPrompt, isInQueue, disableShortsPlayer, showShortsPlayerPrompt) {
        if (disableShortsPlayer || !showShortsPlayerPrompt) {
            screenState.showShortsPrompt = false
            return@LaunchedEffect
        }

        if (!isInQueue && !screenState.hasShownShortsPrompt && videoDuration > 0 && videoDuration <= 80) {
            delay(1000)
            if (!disableShortsPlayer && showShortsPlayerPrompt) {
                screenState.showShortsPrompt = true
                screenState.hasShownShortsPrompt = true
            }
        }
    }
}

@Composable
internal fun SubscriptionAndLikeEffect(
    videoId: String,
    uiState: VideoPlayerUiState,
    viewModel: VideoPlayerViewModel,
) {
    // Keyed on the cached video: the channel id arrives with it, and keying this on the extractor
    // result is what stopped the subscribe button and the like state ever loading once the load
    // stopped producing one. The video id is a key too, or the next video from the same channel
    // kept the previous video's collector.
    LaunchedEffect(uiState.cachedVideo?.channelId, videoId) {
        val channelId = uiState.cachedVideo?.channelId.orEmpty()
        if (channelId.isNotEmpty()) {
            viewModel.loadSubscriptionAndLikeState(channelId, videoId)
        }
    }
}

@Composable
internal fun SponsorSkipEffect(
    context: Context,
    showSkipNotice: Boolean,
    onSkipped: (category: String, skippedMs: Long) -> Unit,
) {
    val currentOnSkipped by rememberUpdatedState(onSkipped)
    val currentShowSkipNotice by rememberUpdatedState(showSkipNotice)
    LaunchedEffect(Unit) {
        // Rapid taps back into a segment skip it each time; toasts would otherwise queue for seconds.
        var shown: Toast? = null
        EnhancedPlayerManager.getInstance().skipEvent.collect { skip ->
            val segment = skip.segment
            if (skip.firstInVideo) currentOnSkipped(segment.category, ((segment.endTime - segment.startTime) * 1000).toLong())
            if (!currentShowSkipNotice) return@collect
            val message = context.getString(R.string.ui_skipped_segment, context.sponsorCategoryLabel(segment.category))
            shown?.cancel()
            shown = Toast.makeText(context, message, Toast.LENGTH_SHORT).also { it.show() }
        }
    }
    LaunchedEffect(Unit) {
        EnhancedPlayerManager.getInstance().sbToastEvent.collect { segment ->
            val message = context.getString(R.string.sb_segment_notice, context.sponsorCategoryLabel(segment.category))
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}

private fun Context.sponsorCategoryLabel(category: String): String = sponsorCategoryLabelRes(category)?.let(::getString) ?: category

@Composable
internal fun SubtitleLoadErrorEffect(
    context: Context,
    subtitles: List<SubtitleOption>,
    rememberLanguage: (String) -> Unit,
) {
    val currentSubtitles by rememberUpdatedState(subtitles)
    LaunchedEffect(Unit) {
        EnhancedPlayerManager.getInstance().subtitleLoadFailedEvent.collect { failure ->
            val options = currentSubtitles
            val fallback =
                SubtitleSelection.fallbackIndexFor(
                    subtitles = options,
                    failedIndex = failure.index,
                    failedLanguage = failure.language,
                    wasTranslated = failure.isTranslated,
                )
            val message =
                if (fallback != null && SubtitleSelection.applyAt(options, fallback, rememberLanguage)) {
                    context.getString(R.string.subtitle_translation_unavailable, options[fallback].label)
                } else {
                    SubtitleSelection.disable()
                    context.getString(R.string.subtitle_load_failed, failure.label)
                }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
