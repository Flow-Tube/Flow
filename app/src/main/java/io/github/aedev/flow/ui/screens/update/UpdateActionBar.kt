@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.aedev.flow.ui.screens.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.layout.LocalFlowBottomInsets
import io.github.aedev.flow.ui.components.shared.FlowActionButton
import io.github.aedev.flow.ui.components.shared.FlowMaxContentWidth

private val BarPadding = 20.dp
private val BarSpacing = 12.dp
private const val PERCENT = 100

/** "Not now" or Cancel, then the one action the current stage offers, filling with download or install progress. */
@Composable
internal fun UpdateActionBar(
    stage: UpdateStage,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
) {
    Box(
        Modifier.fillMaxWidth().padding(bottom = LocalFlowBottomInsets.current.contentBottom),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.widthIn(max = FlowMaxContentWidth).fillMaxWidth().padding(BarPadding),
            horizontalArrangement = Arrangement.spacedBy(BarSpacing, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onSecondary,
                enabled = stage !is UpdateStage.Installing,
                modifier = Modifier.heightIn(min = ButtonDefaults.MediumContainerHeight),
            ) {
                Text(stringResource(if (stage is UpdateStage.Downloading) R.string.cancel else R.string.update_not_now))
            }
            val action = stage.action()
            val fill =
                when (stage) {
                    is UpdateStage.Downloading -> stage.progress ?: 0f
                    is UpdateStage.Installing -> stage.progress ?: 0f
                    else -> null
                }
            FlowActionButton(
                text = action.label(),
                onClick = onPrimary,
                enabled = action.enabled,
                leading = action.icon,
                progress = fill?.let { { it } },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private class StageAction(
    val labelRes: Int,
    val icon: ImageVector?,
    val enabled: Boolean = true,
    val percent: Int? = null,
) {
    @Composable
    fun label(): String = if (percent != null) stringResource(labelRes, percent) else stringResource(labelRes)
}

private fun UpdateStage.action(): StageAction =
    when (this) {
        UpdateStage.Available -> {
            StageAction(R.string.update_action_download, Icons.Outlined.Download)
        }

        is UpdateStage.Downloading -> {
            progress?.let { StageAction(R.string.update_action_downloading_percent, null, percent = (it * PERCENT).toInt()) }
                ?: StageAction(R.string.update_action_downloading, null)
        }

        UpdateStage.Verifying -> {
            StageAction(R.string.update_action_verifying, Icons.Outlined.VerifiedUser, enabled = false)
        }

        UpdateStage.NeedsPermission -> {
            StageAction(R.string.update_action_allow, Icons.AutoMirrored.Outlined.OpenInNew)
        }

        UpdateStage.Ready -> {
            StageAction(R.string.update_action_install, Icons.Outlined.InstallMobile)
        }

        is UpdateStage.Installing -> {
            StageAction(R.string.update_action_installing, null, enabled = false)
        }

        is UpdateStage.Failed -> {
            StageAction(R.string.action_retry, Icons.Outlined.Refresh)
        }
    }
