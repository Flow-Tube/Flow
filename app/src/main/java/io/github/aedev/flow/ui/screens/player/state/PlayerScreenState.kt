package io.github.aedev.flow.ui.screens.player.state

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import io.github.aedev.flow.ui.components.shared.CommentSortFilter
import io.github.aedev.flow.ui.components.shared.CommentThreadState
import io.github.aedev.flow.ui.components.videoplayer.settings.PlayerSettingsPage
import io.github.aedev.flow.ui.components.videoplayer.subtitle.SubtitleStyle

// Every property is snapshot state, so composables taking this instance can skip on identity.
@Stable
class PlayerScreenState {
    // UI Visibility States
    var showControls by mutableStateOf(true)
    var showStatsForNerds by mutableStateOf(false)
    var isTouchLocked by mutableStateOf(false)
    var lockOverlayRevealSignal by mutableIntStateOf(0)
    var isFullscreen by mutableStateOf(false)
    var isFullscreenPortrait by mutableStateOf(false)
    var lastInteractionTimestamp by mutableLongStateOf(System.currentTimeMillis())

    var isScrubbing by mutableStateOf(false)

    var isSeekDragging by mutableStateOf(false)
    var seekDragTargetMs by mutableLongStateOf(0L)
    var seekDragDeltaMs by mutableLongStateOf(0L)

    // Playback Position
    var currentPosition by mutableLongStateOf(0L)
    var bufferedPosition by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)

    // Sheets, panels and dialogs (exactly one at a time)
    internal var activeSheet by mutableStateOf<PlayerSheet>(PlayerSheet.None)

    // The tablet side column's own show/hide toggle, not a sheet: it survives a sheet dismissal.
    // Closed until tapped, because an open column is a running poll (#1042).
    var showLiveChatPanel by mutableStateOf(false)

    // Comment Sorting
    var commentSortFilter by mutableStateOf(CommentSortFilter.TOP)

    // Outlives the comments sheet and panel, which leave composition on every close, collapse and rotation.
    var commentsListState by mutableStateOf(LazyListState())
        private set
    val commentThreads = CommentThreadState()

    // Written by the host from the player sheet's settled anchor; the comments sheet outlives a collapse.
    var isPlayerExpanded by mutableStateOf(true)

    // Gesture States
    var brightnessLevel by mutableFloatStateOf(0.5f)
    var volumeLevel by mutableFloatStateOf(0.5f)
    var showBrightnessOverlay by mutableStateOf(false)
    var showVolumeOverlay by mutableStateOf(false)
    var showSpeedSwipeOverlay by mutableStateOf(false)
    var speedSwipeLevel by mutableFloatStateOf(1.0f)

    // Seek Animation States
    var showSeekForwardAnimation by mutableStateOf(false)
    var seekAccumulation by mutableIntStateOf(10)
    var showSeekBackAnimation by mutableStateOf(false)

    /** A tap just lifted and the double-tap timeout is still running. */
    var isTapWindowOpen by mutableStateOf(false)

    // Subtitle States
    var subtitleStyle by mutableStateOf(SubtitleStyle())

    // The caption track the transcript is read from. Separate from the subtitle choice: reading
    // a transcript in another language should not put that language on top of the video.
    var selectedTranscriptUrl by mutableStateOf<String?>(null)

    // Video Display
    var resizeMode by mutableIntStateOf(0) // 0=Fit, 1=Fill, 2=Zoom

    // Pinch-to-Zoom State
    var zoomScale by mutableFloatStateOf(1f)
    var zoomOffsetX by mutableFloatStateOf(0f)
    var zoomOffsetY by mutableFloatStateOf(0f)
    var showZoomIndicator by mutableStateOf(false)
    var zoomIndicatorSequence by mutableIntStateOf(0)
    var exitDragOffsetY by mutableFloatStateOf(0f)

    // How far a swipe up has pulled the more videos row in before it commits to opening.
    var moreVideosDragY by mutableFloatStateOf(0f)

    // Speed Control
    var isSpeedBoostActive by mutableStateOf(false)
    var normalSpeed by mutableFloatStateOf(1.0f)

    // Shorts/Music Prompt
    var showShortsPrompt by mutableStateOf(false)
    var hasShownShortsPrompt by mutableStateOf(false)

    fun resetForNewVideo() {
        lastInteractionTimestamp = System.currentTimeMillis()
        showControls = true
        isScrubbing = false
        isSeekDragging = false
        seekDragTargetMs = 0L
        seekDragDeltaMs = 0L
        isFullscreenPortrait = false
        isTouchLocked = false
        lockOverlayRevealSignal = 0
        currentPosition = 0L
        duration = 0L
        commentSortFilter = CommentSortFilter.TOP
        resetCommentsPlace()
        selectedTranscriptUrl = null
        showBrightnessOverlay = false
        showVolumeOverlay = false
        showSpeedSwipeOverlay = false
        showSeekBackAnimation = false
        showSeekForwardAnimation = false
        hasShownShortsPrompt = false
        showShortsPrompt = false
        activeSheet = PlayerSheet.None
        showLiveChatPanel = false
        zoomScale = 1f
        zoomOffsetX = 0f
        zoomOffsetY = 0f
        showZoomIndicator = false
        zoomIndicatorSequence = 0
        exitDragOffsetY = 0f
        moreVideosDragY = 0f
    }

    fun changeCommentSort(filter: CommentSortFilter) {
        if (filter == commentSortFilter) return
        commentSortFilter = filter
        resetCommentsPlace()
    }

    private fun resetCommentsPlace() {
        commentsListState = LazyListState()
        commentThreads.clear()
    }

    internal fun open(sheet: PlayerSheet) {
        activeSheet = sheet
    }

    internal fun closeSheet() {
        activeSheet = PlayerSheet.None
    }

    internal val isSettingsOpen: Boolean
        get() = activeSheet is PlayerSheet.Settings

    internal val settingsPage: PlayerSettingsPage
        get() = (activeSheet as? PlayerSheet.Settings)?.page ?: PlayerSettingsPage.Main

    /**
     * Re-anchoring the player — collapsing it, entering or leaving fullscreen — drops whatever it
     * had raised over the stage. Now that [activeSheet] holds a single surface this closes *every*
     * sheet: the sleep timer, download, cast and quick-action dialogs that the previous
     * eighteen-boolean state deliberately left standing cannot survive an exclusive state.
     */
    fun dismissMediaSheets() {
        closeSheet()
    }

    fun cycleResizeMode() {
        resizeMode = (resizeMode + 1) % 3
    }

    fun toggleFullscreen() {
        isFullscreenPortrait = false
        isFullscreen = !isFullscreen
    }

    fun onInteraction() {
        lastInteractionTimestamp = System.currentTimeMillis()
    }

    fun revealLockOverlay() {
        lockOverlayRevealSignal++
    }

    fun playbackFraction(): Float = if (duration > 0) (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
}

@Composable
fun rememberPlayerScreenState(): PlayerScreenState = remember { PlayerScreenState() }

internal fun transcriptTrackUrl(
    playerState: io.github.aedev.flow.player.EnhancedPlayerState,
    screenState: PlayerScreenState,
    prefs: VideoPlayerPreferencesState,
    appLanguage: String?,
): String? =
    TranscriptTrackChoice
        .pick(
            tracks = playerState.availableSubtitles,
            pickedUrl = screenState.selectedTranscriptUrl,
            showingUrl = playerState.selectedSubtitleUrl,
            rememberedLanguage = prefs.transcriptLanguage,
            subtitleLanguage = prefs.preferredSubtitleLanguage,
            appLanguage = appLanguage,
        )?.url

internal fun hasTranscriptTrack(playerState: io.github.aedev.flow.player.EnhancedPlayerState): Boolean =
    playerState.availableSubtitles.any(TranscriptTrackChoice::isReadable)
