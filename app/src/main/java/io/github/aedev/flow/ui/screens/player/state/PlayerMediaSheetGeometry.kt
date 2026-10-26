package io.github.aedev.flow.ui.screens.player.state

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private const val FULLSCREEN_MEDIA_SHEET_FRACTION = 0.75f

/** The two heights a media sheet snaps between, plus the unlocked collapsed height it derives from. */
internal data class MediaSheetHeights(
    val rawCollapsed: Dp,
    val collapsed: Dp,
    val expanded: Dp,
)

/**
 * The progress a resize-driven media sheet is at, and the collapsed height it was locked to when
 * the resize began. Held across both halves of the geometry so the height maths inside
 * `BoxWithConstraints` can see what the outer half recorded.
 */
@Stable
internal class MediaSheetProgressState {
    var progress by mutableFloatStateOf(0f)
    var lockedCollapsedHeight by mutableStateOf<Dp?>(null)
}

@Stable
internal class PlayerMediaSheetGeometry(
    val progressState: MediaSheetProgressState,
    val progressDrivenResize: Boolean,
    val effectiveVideoAspectRatio: Float,
    val playerHeightFractionOverride: (() -> Float)?,
    val onProgressChange: (Float) -> Unit,
)

internal fun mediaSheetHeights(
    density: Density,
    fullScreenHeightPx: Float,
    expandedPlayerBottom: Dp,
    isFullscreen: Boolean,
    progressDrivenResize: Boolean,
    lockedCollapsedHeight: Dp?,
    fallbackScreenHeight: Dp,
    statusBarHeightPx: Float,
    playerWidthPx: Float,
): MediaSheetHeights {
    val rawMediaSheetCollapsedHeight =
        with(density) {
            val availablePx = fullScreenHeightPx - expandedPlayerBottom.toPx()
            if (expandedPlayerBottom > 0.dp && availablePx > 0f) {
                availablePx.toDp()
            } else {
                0.dp
            }
        }
    val defaultMediaSheetExpandedHeight =
        with(density) {
            val availablePx = fullScreenHeightPx - expandedPlayerBottom.toPx()
            when {
                // Fullscreen leaves nothing below the player, so a sheet sized to "whatever is
                // left under it" collapses to a sliver pinned at the bottom of the screen. Over
                // a fullscreen player the sheet floats on the video and takes a fixed share.
                isFullscreen -> (fullScreenHeightPx * FULLSCREEN_MEDIA_SHEET_FRACTION).toDp()

                expandedPlayerBottom > 0.dp && availablePx > 0f -> availablePx.toDp()

                else -> fallbackScreenHeight * 0.75f
            }
        }
    val sixteenNineMediaSheetExpandedHeight =
        with(density) {
            val sixteenNinePlayerBottomPx = statusBarHeightPx + playerWidthPx * 9f / 16f
            (fullScreenHeightPx - sixteenNinePlayerBottomPx).coerceAtLeast(0f).toDp()
        }
    val mediaSheetCollapsedHeight =
        if (progressDrivenResize) {
            lockedCollapsedHeight ?: rawMediaSheetCollapsedHeight
        } else {
            0.dp
        }
    val mediaSheetExpandedHeight =
        if (progressDrivenResize) {
            maxOf(mediaSheetCollapsedHeight, sixteenNineMediaSheetExpandedHeight)
        } else {
            defaultMediaSheetExpandedHeight
        }
    return MediaSheetHeights(
        rawCollapsed = rawMediaSheetCollapsedHeight,
        collapsed = mediaSheetCollapsedHeight,
        expanded = mediaSheetExpandedHeight,
    )
}

@Composable
internal fun rememberPlayerMediaSheetGeometry(
    screenState: PlayerScreenState,
    adaptivePlayerSizeEnabled: Boolean,
    videoAspectRatio: Float,
    sheetsHostedBesideVideo: Boolean = false,
): PlayerMediaSheetGeometry {
    val progressState = remember { MediaSheetProgressState() }
    val progressDrivenMediaSheetVisible =
        !sheetsHostedBesideVideo &&
            when (val sheet = screenState.activeSheet) {
                is PlayerSheet.Comments -> !sheet.fullscreen

                is PlayerSheet.LiveChat -> !sheet.fullscreen

                is PlayerSheet.Settings,
                PlayerSheet.Chapters,
                PlayerSheet.Description,
                PlayerSheet.Download,
                PlayerSheet.Queue,
                PlayerSheet.SleepTimer,
                -> true

                else -> false
            }
    val progressDrivenMediaSheetResize =
        adaptivePlayerSizeEnabled &&
            !screenState.isFullscreen &&
            progressDrivenMediaSheetVisible &&
            videoAspectRatio < 1f
    LaunchedEffect(progressDrivenMediaSheetVisible) {
        if (!progressDrivenMediaSheetVisible) {
            if (progressState.progress != 0f) {
                progressState.progress = 0f
            }
            progressState.lockedCollapsedHeight = null
        }
    }
    val updateMediaSheetProgress: (Float) -> Unit = { progress ->
        val nextProgress = progress.coerceIn(0f, 1f)
        if (kotlin.math.abs(progressState.progress - nextProgress) > 0.001f) {
            progressState.progress = nextProgress
        }
    }
    // A provider so the per-frame sheet progress is read in the layout phase, not here.
    val sheetPlayerHeightFractionOverride: (() -> Float)? =
        if (progressDrivenMediaSheetResize) {
            { 1f - progressState.progress.coerceIn(0f, 1f) }
        } else {
            null
        }
    val effectiveVideoAspectRatio =
        when {
            adaptivePlayerSizeEnabled || screenState.isFullscreen -> videoAspectRatio
            else -> 16f / 9f
        }
    return PlayerMediaSheetGeometry(
        progressState = progressState,
        progressDrivenResize = progressDrivenMediaSheetResize,
        effectiveVideoAspectRatio = effectiveVideoAspectRatio,
        playerHeightFractionOverride = sheetPlayerHeightFractionOverride,
        onProgressChange = updateMediaSheetProgress,
    )
}

@Composable
internal fun rememberMediaSheetHeights(
    geometry: PlayerMediaSheetGeometry,
    isFullscreen: Boolean,
    fullScreenHeightPx: Float,
    playerWidthPx: Float,
    expandedPlayerBottom: Dp,
    fallbackScreenHeight: Dp,
    maxWidth: Dp,
    maxHeight: Dp,
): MediaSheetHeights {
    val density = LocalDensity.current
    val statusBarHeightPx = with(density) { WindowInsets.statusBars.getTop(this).toFloat() }
    val heights =
        mediaSheetHeights(
            density = density,
            fullScreenHeightPx = fullScreenHeightPx,
            expandedPlayerBottom = expandedPlayerBottom,
            isFullscreen = isFullscreen,
            progressDrivenResize = geometry.progressDrivenResize,
            lockedCollapsedHeight = geometry.progressState.lockedCollapsedHeight,
            fallbackScreenHeight = fallbackScreenHeight,
            statusBarHeightPx = statusBarHeightPx,
            playerWidthPx = playerWidthPx,
        )
    LaunchedEffect(geometry.progressDrivenResize, heights.rawCollapsed, maxWidth, maxHeight) {
        if (
            geometry.progressDrivenResize &&
            geometry.progressState.lockedCollapsedHeight == null &&
            expandedPlayerBottom > 0.dp
        ) {
            geometry.progressState.lockedCollapsedHeight = heights.rawCollapsed
        } else if (!geometry.progressDrivenResize) {
            geometry.progressState.lockedCollapsedHeight = null
        }
    }
    return heights
}
