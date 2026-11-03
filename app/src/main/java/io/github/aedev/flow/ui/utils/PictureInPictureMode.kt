package io.github.aedev.flow.ui.utils

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer

/**
 * Whether the activity is in picture-in-picture, as the PiP guide reads it: written from the
 * activity's own callback, so the first frame drawn in the PiP window already sees it. A value
 * relayed through a flow and an effect lands a frame or more later, and that frame shows whatever
 * layout the full window had.
 */
@Composable
fun rememberIsInPipMode(): Boolean {
    val activity = LocalActivity.current as? ComponentActivity ?: return false
    var inPipMode by remember(activity) { mutableStateOf(activity.isInPictureInPictureMode) }
    DisposableEffect(activity) {
        val listener = Consumer<PictureInPictureModeChangedInfo> { inPipMode = it.isInPictureInPictureMode }
        activity.addOnPictureInPictureModeChangedListener(listener)
        onDispose { activity.removeOnPictureInPictureModeChangedListener(listener) }
    }
    return inPipMode
}
