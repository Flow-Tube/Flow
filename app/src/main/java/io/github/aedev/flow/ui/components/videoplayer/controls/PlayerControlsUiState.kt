package io.github.aedev.flow.ui.components.videoplayer.controls

import androidx.compose.runtime.Immutable
import io.github.aedev.flow.innertube.models.response.VideoHeatmap
import io.github.aedev.flow.player.stream.StoryboardLevel
import org.schabi.newpipe.extractor.stream.StreamSegment

/**
 * Everything the on-video controls render that is not written per frame.
 *
 * The playhead, the buffered fraction and the gesture levels stay out of here and reach the overlay
 * as providers, so a position tick cannot invalidate this value and recompose the whole tree.
 */
@Immutable
internal data class PlayerControlsUiState(
    val isVisible: Boolean = false,
    val isPlaying: Boolean = false,
    val hasEnded: Boolean = false,
    val isBuffering: Boolean = false,
    val duration: Long = 0L,
    val qualityLabel: String? = null,
    val videoTitle: String? = null,
    val channelName: String? = null,
    val playbackSpeed: Float = 1.0f,
    val resizeMode: Int = 0,
    val isFullscreen: Boolean = false,
    val isPortraitFullscreen: Boolean = false,
    val isPipSupported: Boolean = false,
    val chapters: List<StreamSegment> = emptyList(),
    val storyboard: List<StoryboardLevel> = emptyList(),
    val heatmap: VideoHeatmap? = null,
    val commentMarks: List<Float> = emptyList(),
    val isSubtitlesEnabled: Boolean = false,
    val autoplayEnabled: Boolean = true,
    val isLooping: Boolean = false,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val hasQueue: Boolean = false,
    val sbSubmitEnabled: Boolean = false,
    /** SponsorBlock is on and this video has segments to act on. */
    val isSponsorBlockAvailable: Boolean = false,
    val isSponsorBlockOffForVideo: Boolean = false,
    val isCasting: Boolean = false,
    val isLive: Boolean = false,
    val isLiveChatAvailable: Boolean = false,
    val isCommentsAvailable: Boolean = false,
    val isCommentsPanelOpen: Boolean = false,
    val isSleepTimerActive: Boolean = false,
    val showRemainingTime: Boolean = false,
    val isTouchLocked: Boolean = false,
    val lockModeEnabled: Boolean = false,
    val lockOverlayRevealSignal: Int = 0,
    /** A gesture read-out — the speed badge, the zoom scale — currently owns the picture. */
    val isGestureReadoutActive: Boolean = false,
    /** A double tap is still open or a double-tap seek is running, so the next touch is the player's. */
    val yieldsTapsToPlayer: Boolean = false,
)
