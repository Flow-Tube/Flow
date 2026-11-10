package io.github.aedev.flow.ui.screens.player.stage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import io.github.aedev.flow.ui.components.videoplayer.ambient.AmbientFrameHolder
import io.github.aedev.flow.ui.components.videoplayer.ambient.AmbientStatusBarGlow
import io.github.aedev.flow.ui.components.videoplayer.motion.collapsingGroundAlpha
import io.github.aedev.flow.ui.screens.player.state.PlayerLayoutMode
import io.github.aedev.flow.ui.screens.player.state.VideoPlayerPreferencesState
import io.github.aedev.flow.ui.utils.rememberPowerSaveMode

/**
 * The player's ambient light: the one frame every surface reads, and whether the page around the
 * player glows. [glow] holds only while the sheet is at rest open and stays on through a collapse,
 * so the glow fades out with the page instead of vanishing under the finger. [immersive] lights
 * the whole page instead of the band under the player. Both pause in battery saver unless the
 * viewer asked to keep them (#1914).
 */
@Immutable
internal class PlayerAmbient(
    val frames: AmbientFrameHolder,
    val glow: Boolean,
    val immersive: Boolean,
)

@Composable
internal fun rememberPlayerAmbient(
    prefs: VideoPlayerPreferencesState,
    isFullscreenShown: Boolean,
    expandedSurfacesMounted: Boolean,
    isInPipMode: Boolean,
    layoutMode: PlayerLayoutMode,
): PlayerAmbient {
    val frames = remember { AmbientFrameHolder() }
    // Listened to only while battery saver can still turn the glow off.
    val powerSaving = prefs.ambientGlowEnabled && !prefs.ambientGlowInBatterySaver && rememberPowerSaveMode()
    val glow =
        prefs.ambientGlowEnabled &&
            !powerSaving &&
            expandedSurfacesMounted &&
            !isInPipMode &&
            !isFullscreenShown &&
            layoutMode != PlayerLayoutMode.WIDE
    val immersive = glow && prefs.ambientImmersiveEnabled
    return remember(frames, glow, immersive) { PlayerAmbient(frames, glow, immersive) }
}

/** The glow behind the status bar, fading with the ground it covers as the sheet collapses. */
@Composable
internal fun PlayerAmbientStatusBar(
    session: VideoPlayerStageSession,
    statusBarHeight: Float,
) {
    if (!session.ambient.glow) return
    val sheet = session.sheetState
    val alpha = remember(sheet) { { collapsingGroundAlpha(sheet.fraction) } }
    AmbientStatusBarGlow(frames = session.ambient.frames, heightPx = statusBarHeight, alpha = alpha)
}
