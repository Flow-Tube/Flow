package io.github.aedev.flow.ui.screens.settings.playback

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.settings.isNew
import io.github.aedev.flow.ui.components.shared.FlowSwitchRow
import io.github.aedev.flow.ui.screens.settings.index.PlaybackIndex
import kotlinx.coroutines.flow.StateFlow

/**
 * The lock screen player switch. It only reads as on while "Display over other apps" is granted, since
 * the player can't open without it; turning it on without the permission opens the system page first.
 */
@Composable
internal fun LockScreenPlayerSwitch(
    state: StateFlow<Boolean>,
    onCheckedChange: (Boolean) -> Unit,
    shape: Shape,
) {
    val context = LocalContext.current
    val saved by state.collectAsStateWithLifecycle()
    var canDraw by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    LifecycleResumeEffect(Unit) {
        canDraw = Settings.canDrawOverlays(context)
        onPauseOrDispose { }
    }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            canDraw = Settings.canDrawOverlays(context)
            if (canDraw) onCheckedChange(true)
        }
    val entry = PlaybackIndex.lockScreenPlayer
    FlowSwitchRow(
        title = stringResource(entry.title),
        supportingText =
            stringResource(
                if (saved && !canDraw) R.string.settings_lock_screen_player_permission else R.string.settings_lock_screen_player_summary,
            ),
        checked = saved && canDraw,
        onCheckedChange = { on ->
            when {
                !on -> {
                    onCheckedChange(false)
                }

                canDraw -> {
                    onCheckedChange(true)
                }

                else -> {
                    runCatching {
                        permission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                    }
                }
            }
        },
        shape = shape,
        titleBadge = entry.isNew(),
    )
}
