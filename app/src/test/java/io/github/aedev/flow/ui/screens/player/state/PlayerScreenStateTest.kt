package io.github.aedev.flow.ui.screens.player.state

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.aedev.flow.ui.components.shared.CommentSortFilter
import io.github.aedev.flow.ui.components.videoplayer.settings.PlayerSettingsPage
import io.github.aedev.flow.ui.components.videoplayer.subtitle.SubtitleStyle
import org.junit.Test

/**
 * Pins the exact shape of [PlayerScreenState]: what every property starts as and precisely which
 * ones each mutator touches. The tables are exhaustive on purpose, so a property added without a
 * decision about the reset paths fails a test rather than slipping through.
 */
class PlayerScreenStateTest {
    private val defaults: Map<String, Any?> =
        mapOf(
            "showControls" to true,
            "isTouchLocked" to false,
            "lockOverlayRevealSignal" to 0,
            "isFullscreen" to false,
            "isFullscreenPortrait" to false,
            "isScrubbing" to false,
            "isSeekDragging" to false,
            "seekDragTargetMs" to 0L,
            "seekDragDeltaMs" to 0L,
            "currentPosition" to 0L,
            "bufferedPosition" to 0L,
            "duration" to 0L,
            "activeSheet" to PlayerSheet.None,
            "showLiveChatPanel" to false,
            "commentSortFilter" to CommentSortFilter.TOP,
            "brightnessLevel" to 0.5f,
            "volumeLevel" to 0.5f,
            "showBrightnessOverlay" to false,
            "showVolumeOverlay" to false,
            "showSpeedSwipeOverlay" to false,
            "speedSwipeLevel" to 1.0f,
            "showSeekForwardAnimation" to false,
            "seekAccumulation" to 10,
            "showSeekBackAnimation" to false,
            "subtitleStyle" to SubtitleStyle(),
            "resizeMode" to 0,
            "zoomScale" to 1f,
            "zoomOffsetX" to 0f,
            "zoomOffsetY" to 0f,
            "showZoomIndicator" to false,
            "zoomIndicatorSequence" to 0,
            "exitDragOffsetY" to 0f,
            "isSpeedBoostActive" to false,
            "normalSpeed" to 1.0f,
            "showShortsPrompt" to false,
            "hasShownShortsPrompt" to false,
        )

    private val resetForNewVideoTouches =
        setOf(
            "commentSortFilter",
            "showControls",
            "isScrubbing",
            "isSeekDragging",
            "seekDragTargetMs",
            "seekDragDeltaMs",
            "isFullscreenPortrait",
            "isTouchLocked",
            "lockOverlayRevealSignal",
            "currentPosition",
            "duration",
            "showBrightnessOverlay",
            "showVolumeOverlay",
            "showSpeedSwipeOverlay",
            "showSeekBackAnimation",
            "showSeekForwardAnimation",
            "hasShownShortsPrompt",
            "showShortsPrompt",
            "activeSheet",
            "showLiveChatPanel",
            "zoomScale",
            "zoomOffsetX",
            "zoomOffsetY",
            "showZoomIndicator",
            "zoomIndicatorSequence",
            "exitDragOffsetY",
        )

    // Pins current behaviour: bufferedPosition, isSpeedBoostActive and seekAccumulation
    // survive a video change alongside the deliberately persistent display and gesture preferences.
    private val resetForNewVideoLeaves =
        setOf(
            "isFullscreen",
            "bufferedPosition",
            "brightnessLevel",
            "volumeLevel",
            "speedSwipeLevel",
            "seekAccumulation",
            "subtitleStyle",
            "resizeMode",
            "isSpeedBoostActive",
            "normalSpeed",
        )

    private val dismissMediaSheetsClears = setOf("activeSheet")

    /**
     * The eighteen-flag state deliberately left the sleep timer, download and cast
     * dialogs standing through a dismissal. One exclusive [PlayerSheet] cannot express that carve
     * out, so they close with everything else now.
     */
    private val dismissMediaSheetsAlsoClosesNow =
        listOf(
            PlayerSheet.SleepTimer,
            PlayerSheet.Download,
            PlayerSheet.Dlna,
        )

    /** Every snapshot-backed property except the wall-clock timestamp, keyed by name. */
    private fun PlayerScreenState.snapshot(): Map<String, Any?> =
        mapOf(
            "showControls" to showControls,
            "isTouchLocked" to isTouchLocked,
            "lockOverlayRevealSignal" to lockOverlayRevealSignal,
            "isFullscreen" to isFullscreen,
            "isFullscreenPortrait" to isFullscreenPortrait,
            "isScrubbing" to isScrubbing,
            "isSeekDragging" to isSeekDragging,
            "seekDragTargetMs" to seekDragTargetMs,
            "seekDragDeltaMs" to seekDragDeltaMs,
            "currentPosition" to currentPosition,
            "bufferedPosition" to bufferedPosition,
            "duration" to duration,
            "activeSheet" to activeSheet,
            "showLiveChatPanel" to showLiveChatPanel,
            "commentSortFilter" to commentSortFilter,
            "brightnessLevel" to brightnessLevel,
            "volumeLevel" to volumeLevel,
            "showBrightnessOverlay" to showBrightnessOverlay,
            "showVolumeOverlay" to showVolumeOverlay,
            "showSpeedSwipeOverlay" to showSpeedSwipeOverlay,
            "speedSwipeLevel" to speedSwipeLevel,
            "showSeekForwardAnimation" to showSeekForwardAnimation,
            "seekAccumulation" to seekAccumulation,
            "showSeekBackAnimation" to showSeekBackAnimation,
            "subtitleStyle" to subtitleStyle,
            "resizeMode" to resizeMode,
            "zoomScale" to zoomScale,
            "zoomOffsetX" to zoomOffsetX,
            "zoomOffsetY" to zoomOffsetY,
            "showZoomIndicator" to showZoomIndicator,
            "zoomIndicatorSequence" to zoomIndicatorSequence,
            "exitDragOffsetY" to exitDragOffsetY,
            "isSpeedBoostActive" to isSpeedBoostActive,
            "normalSpeed" to normalSpeed,
            "showShortsPrompt" to showShortsPrompt,
            "hasShownShortsPrompt" to hasShownShortsPrompt,
        )

    /** Moves every property off its default so an untouched field is detectable after a mutator. */
    private fun PlayerScreenState.dirtyEverything() {
        showControls = false
        isTouchLocked = true
        lockOverlayRevealSignal = 3
        isFullscreen = true
        isFullscreenPortrait = true
        lastInteractionTimestamp = 1L
        isScrubbing = true
        isSeekDragging = true
        seekDragTargetMs = 1_234L
        seekDragDeltaMs = -500L
        currentPosition = 90_000L
        bufferedPosition = 120_000L
        duration = 600_000L
        activeSheet = PlayerSheet.Settings(PlayerSettingsPage.Quality)
        showLiveChatPanel = true
        commentSortFilter = CommentSortFilter.NEWEST
        brightnessLevel = 0.9f
        volumeLevel = 0.1f
        showBrightnessOverlay = true
        showVolumeOverlay = true
        showSpeedSwipeOverlay = true
        speedSwipeLevel = 2.5f
        showSeekForwardAnimation = true
        seekAccumulation = 30
        showSeekBackAnimation = true
        subtitleStyle = SubtitleStyle(fontSize = 22f)
        resizeMode = 2
        zoomScale = 2.5f
        zoomOffsetX = 40f
        zoomOffsetY = -40f
        showZoomIndicator = true
        zoomIndicatorSequence = 4
        exitDragOffsetY = 300f
        isSpeedBoostActive = true
        normalSpeed = 1.5f
        showShortsPrompt = true
        hasShownShortsPrompt = true
    }

    @Test
    fun `a fresh state matches the defaults table`() {
        val before = System.currentTimeMillis()
        val state = PlayerScreenState()
        val after = System.currentTimeMillis()

        assertThat(state.snapshot()).containsExactlyEntriesIn(defaults)
        assertThat(state.lastInteractionTimestamp).isAtLeast(before)
        assertThat(state.lastInteractionTimestamp).isAtMost(after)
    }

    @Test
    fun `the dirty fixture moves every property off its default`() {
        val dirty = PlayerScreenState().apply { dirtyEverything() }.snapshot()

        assertThat(dirty.keys).containsExactlyElementsIn(defaults.keys)
        dirty.forEach { (name, value) -> assertWithMessage(name).that(value).isNotEqualTo(defaults[name]) }
    }

    @Test
    fun `the reset tables partition every property`() {
        assertThat(resetForNewVideoTouches + resetForNewVideoLeaves).containsExactlyElementsIn(defaults.keys)
        assertThat(resetForNewVideoTouches intersect resetForNewVideoLeaves).isEmpty()
    }

    @Test
    fun `resetForNewVideo resets exactly the per video fields`() {
        val state = PlayerScreenState().apply { dirtyEverything() }
        val dirty = state.snapshot()
        val before = System.currentTimeMillis()

        state.resetForNewVideo()

        val expected = dirty + defaults.filterKeys { it in resetForNewVideoTouches }
        assertThat(state.snapshot()).containsExactlyEntriesIn(expected)
        assertThat(state.lastInteractionTimestamp).isAtLeast(before)
        resetForNewVideoLeaves.forEach { name ->
            assertWithMessage(name).that(state.snapshot()[name]).isEqualTo(dirty[name])
        }
    }

    @Test
    fun `resetForNewVideo starts the comments at the top with every thread closed`() {
        val state = PlayerScreenState()
        val listState = state.commentsListState
        state.commentThreads.toggleReplies("a")
        state.commentThreads.expandText("b")

        state.resetForNewVideo()

        assertThat(state.commentsListState).isNotSameInstanceAs(listState)
        assertThat(state.commentThreads.isRepliesOpen("a")).isFalse()
        assertThat(state.commentThreads.isTextExpanded("b")).isFalse()
    }

    @Test
    fun `closing the comments keeps their place and threads`() {
        val state = PlayerScreenState()
        val listState = state.commentsListState
        state.open(PlayerSheet.Comments())
        state.commentThreads.toggleReplies("a")

        state.dismissMediaSheets()
        state.isFullscreen = true

        assertThat(state.commentsListState).isSameInstanceAs(listState)
        assertThat(state.commentThreads.isRepliesOpen("a")).isTrue()
    }

    @Test
    fun `changing the sort resets the comments place and picking the same sort keeps it`() {
        val state = PlayerScreenState()
        val listState = state.commentsListState
        state.commentThreads.toggleReplies("a")

        state.changeCommentSort(CommentSortFilter.TOP)
        assertThat(state.commentsListState).isSameInstanceAs(listState)
        assertThat(state.commentThreads.isRepliesOpen("a")).isTrue()

        state.changeCommentSort(CommentSortFilter.NEWEST)
        assertThat(state.commentSortFilter).isEqualTo(CommentSortFilter.NEWEST)
        assertThat(state.commentsListState).isNotSameInstanceAs(listState)
        assertThat(state.commentThreads.isRepliesOpen("a")).isFalse()
    }

    @Test
    fun `dismissMediaSheets closes the open sheet and leaves the side column toggle alone`() {
        val state = PlayerScreenState().apply { dirtyEverything() }
        val dirty = state.snapshot()

        state.dismissMediaSheets()

        val expected = dirty + dismissMediaSheetsClears.associateWith { PlayerSheet.None }
        assertThat(state.snapshot()).containsExactlyEntriesIn(expected)
        assertThat(state.showLiveChatPanel).isTrue()
        assertThat(state.lastInteractionTimestamp).isEqualTo(1L)
    }

    @Test
    fun `dismissMediaSheets also closes the dialogs the flag state used to leave standing`() {
        dismissMediaSheetsAlsoClosesNow.forEach { sheet ->
            val state = PlayerScreenState().apply { open(sheet) }

            state.dismissMediaSheets()

            assertWithMessage(sheet.toString()).that(state.activeSheet).isEqualTo(PlayerSheet.None)
        }
    }

    @Test
    fun `open replaces whatever sheet was showing and closeSheet clears it`() {
        val state = PlayerScreenState()

        state.open(PlayerSheet.Chapters)
        assertThat(state.activeSheet).isEqualTo(PlayerSheet.Chapters)

        state.open(PlayerSheet.Comments(fullscreen = true))
        assertThat(state.activeSheet).isEqualTo(PlayerSheet.Comments(fullscreen = true))

        state.closeSheet()
        assertThat(state.activeSheet).isEqualTo(PlayerSheet.None)
    }

    @Test
    fun `settingsPage reports the page of an open settings sheet and Main otherwise`() {
        val state = PlayerScreenState()

        assertThat(state.isSettingsOpen).isFalse()
        assertThat(state.settingsPage).isEqualTo(PlayerSettingsPage.Main)

        state.open(PlayerSheet.Settings(PlayerSettingsPage.Subtitles))
        assertThat(state.isSettingsOpen).isTrue()
        assertThat(state.settingsPage).isEqualTo(PlayerSettingsPage.Subtitles)

        state.open(PlayerSheet.Chapters)
        assertThat(state.isSettingsOpen).isFalse()
        assertThat(state.settingsPage).isEqualTo(PlayerSettingsPage.Main)
    }

    @Test
    fun `cycleResizeMode wraps fit fill zoom`() {
        val state = PlayerScreenState()

        val seen = mutableListOf(state.resizeMode)
        repeat(3) {
            state.cycleResizeMode()
            seen += state.resizeMode
        }

        assertThat(seen).containsExactly(0, 1, 2, 0).inOrder()
    }

    @Test
    fun `toggleFullscreen flips the flag and always leaves portrait fullscreen`() {
        val state = PlayerScreenState().apply { isFullscreenPortrait = true }

        state.toggleFullscreen()
        assertThat(state.isFullscreen).isTrue()
        assertThat(state.isFullscreenPortrait).isFalse()

        state.isFullscreenPortrait = true
        state.toggleFullscreen()
        assertThat(state.isFullscreen).isFalse()
        assertThat(state.isFullscreenPortrait).isFalse()
    }

    @Test
    fun `revealLockOverlay counts up without touching the lock itself`() {
        val state = PlayerScreenState().apply { isTouchLocked = true }

        state.revealLockOverlay()
        state.revealLockOverlay()

        assertThat(state.lockOverlayRevealSignal).isEqualTo(2)
        assertThat(state.isTouchLocked).isTrue()
    }

    @Test
    fun `onInteraction refreshes the timestamp and nothing else`() {
        val state = PlayerScreenState().apply { dirtyEverything() }
        val dirty = state.snapshot()
        val before = System.currentTimeMillis()

        state.onInteraction()

        assertThat(state.lastInteractionTimestamp).isAtLeast(before)
        assertThat(state.snapshot()).containsExactlyEntriesIn(dirty)
    }
}
