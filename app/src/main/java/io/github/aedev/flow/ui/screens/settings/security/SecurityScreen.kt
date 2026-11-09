package io.github.aedev.flow.ui.screens.settings.security

import android.os.Build
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.applock.APP_LOCK_DELAYS_MINUTES
import io.github.aedev.flow.applock.DEFAULT_APP_LOCK_DELAY_MINUTES
import io.github.aedev.flow.ui.components.applock.rememberAppUnlock
import io.github.aedev.flow.ui.components.settings.SettingsPage
import io.github.aedev.flow.ui.components.settings.choice
import io.github.aedev.flow.ui.components.settings.switch
import io.github.aedev.flow.ui.components.shared.FlowChoice
import io.github.aedev.flow.ui.components.shared.FlowChoiceDialog
import io.github.aedev.flow.ui.screens.settings.index.SecurityIndex

/**
 * The app lock. Turning it on or off asks for the phone's unlock first, and it needs a screen lock on
 * the phone; one that is removed later can still be turned off here.
 */
@Composable
internal fun SecurityScreen(
    onBack: (() -> Unit)?,
    highlight: String?,
    viewModel: SecurityViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var deviceSecure by remember { mutableStateOf(viewModel.isDeviceSecure()) }
    LifecycleResumeEffect(Unit) {
        deviceSecure = viewModel.isDeviceSecure()
        onPauseOrDispose { }
    }
    var requested by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val unlock =
        rememberAppUnlock(viewModel.appLock) { succeeded ->
            val wanted = requested
            requested = null
            if (succeeded && wanted != null) viewModel.setEnabled(wanted)
        }
    var choosingDelay by rememberSaveable { mutableStateOf(false) }

    val enabled = settings?.enabled == true
    val delayMinutes = settings?.delayMinutes ?: DEFAULT_APP_LOCK_DELAY_MINUTES
    val lockSummary = if (deviceSecure) null else stringResource(R.string.app_lock_needs_screen_lock)
    val recentsSummary =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) stringResource(R.string.app_lock_hide_in_recents_summary_secure) else null

    SettingsPage(
        title = stringResource(R.string.settings_security_title),
        onBack = onBack,
        highlight = highlight,
    ) {
        group(key = "security.lock", footer = R.string.app_lock_footer) {
            switch(
                SecurityIndex.appLock,
                checked = enabled,
                onCheckedChange = { on ->
                    if (!on && !deviceSecure) {
                        viewModel.setEnabled(false)
                    } else {
                        requested = on
                        unlock()
                    }
                },
                enabled = deviceSecure || enabled,
                summary = lockSummary,
                icon = Icons.Outlined.Lock,
            )
            if (enabled) {
                choice(SecurityIndex.lockAfter, onClick = { choosingDelay = true }, icon = Icons.Outlined.Timer) {
                    delayLabel(delayMinutes)
                }
                switch(
                    SecurityIndex.hideInRecents,
                    checked = settings?.hideInRecents == true,
                    onCheckedChange = viewModel::setHideInRecents,
                    summary = recentsSummary,
                    icon = Icons.Outlined.VisibilityOff,
                )
            }
        }
    }

    if (choosingDelay) {
        FlowChoiceDialog(
            title = stringResource(R.string.app_lock_after_title),
            description = stringResource(R.string.app_lock_after_description),
            options = APP_LOCK_DELAYS_MINUTES.map { FlowChoice(it, delayLabel(it)) },
            selected = delayMinutes,
            onSelect = viewModel::setDelayMinutes,
            onDismiss = { choosingDelay = false },
        )
    }
}

@Composable
private fun delayLabel(minutes: Int): String =
    if (minutes == 0) {
        stringResource(R.string.app_lock_after_immediately)
    } else {
        pluralStringResource(R.plurals.app_lock_after_minutes, minutes, minutes)
    }
