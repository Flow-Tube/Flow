package io.github.aedev.flow.ui.components.musicplayer.mini

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.aedev.flow.data.local.CoverShape
import io.github.aedev.flow.data.local.DefaultCoverShapes
import io.github.aedev.flow.data.local.MiniBarProgress
import io.github.aedev.flow.data.local.MiniCoverMotion
import io.github.aedev.flow.data.local.MiniCoverShape
import io.github.aedev.flow.data.local.PlayerPreferences

/** How the music mini player looks, as chosen in Appearance › Player. */
@Immutable
internal data class MiniPlayerLook(
    val coverShape: MiniCoverShape = MiniCoverShape.CIRCLE,
    val coverMotion: MiniCoverMotion = MiniCoverMotion.ON_PLAY,
    val coverShapes: List<CoverShape> = DefaultCoverShapes,
    val pausedShape: CoverShape? = CoverShape.CIRCLE,
    val barProgress: MiniBarProgress = MiniBarProgress.NONE,
    val coverRing: Boolean = true,
) {
    companion object {
        /** Today's bar, for hosts with no choices of their own, such as TV. */
        val Plain = MiniPlayerLook()
    }
}

/** The user's mini player look, read once per host. */
@Composable
internal fun rememberMiniPlayerLook(): MiniPlayerLook {
    val context = LocalContext.current
    val preferences = remember(context) { PlayerPreferences(context) }
    val coverShape by preferences.miniCoverShape.collectAsState(initial = MiniCoverShape.MORPHING)
    val coverMotion by preferences.miniCoverMotion.collectAsState(initial = MiniCoverMotion.ON_PLAY)
    val coverShapes by preferences.miniCoverShapes.collectAsState(initial = DefaultCoverShapes)
    val pausedShape by preferences.miniCoverPausedShape.collectAsState(initial = CoverShape.CIRCLE)
    val barProgress by preferences.miniBarProgress.collectAsState(initial = MiniBarProgress.NONE)
    val coverRing by preferences.miniCoverRing.collectAsState(initial = true)
    return remember(coverShape, coverMotion, coverShapes, pausedShape, barProgress, coverRing) {
        MiniPlayerLook(coverShape, coverMotion, coverShapes, pausedShape, barProgress, coverRing)
    }
}
