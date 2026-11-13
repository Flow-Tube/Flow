package io.github.aedev.flow.ui.components.videoplayer.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.player.stats.PlayerStatLine
import io.github.aedev.flow.ui.theme.PlayerScrimContent
import io.github.aedev.flow.ui.theme.PlayerScrimPanel

private val PanelMaxWidth = 520.dp

/**
 * "Stats for nerds", over the top of the video. Taps away from its two buttons reach the player, so
 * nothing here may take pointer input: a scroller would swallow every touch over the controls. The
 * buttons start at [actionsTop], below the player's top bar, so they never sit on its buttons (#2022).
 */
@Composable
internal fun PlayerStatsOverlay(
    lines: List<PlayerStatLine>,
    onCopy: (String) -> Unit,
    onClose: () -> Unit,
    actionsTop: Dp,
    modifier: Modifier = Modifier,
) {
    val rows =
        lines.map { line ->
            val value = line.valueRes?.let { stringResource(it) } ?: line.value
            stringResource(R.string.stats_line, stringResource(line.label), value)
        }
    Box(
        modifier =
            modifier
                .widthIn(max = PanelMaxWidth)
                .fillMaxWidth()
                .background(PlayerScrimPanel)
                .clipToBounds(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 8.dp, end = 48.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            rows.forEach { row ->
                Text(text = row, style = MaterialTheme.typography.labelSmall, color = PlayerScrimContent)
            }
        }
        Column(
            modifier = Modifier.align(Alignment.TopEnd).padding(top = actionsTop),
            horizontalAlignment = Alignment.End,
        ) {
            IconButton(
                onClick = onClose,
                colors = IconButtonDefaults.iconButtonColors(contentColor = PlayerScrimContent),
            ) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.close))
            }
            TextButton(
                onClick = { onCopy(rows.joinToString("\n")) },
                colors = ButtonDefaults.textButtonColors(contentColor = PlayerScrimContent),
            ) {
                Text(stringResource(R.string.stats_copy))
            }
        }
    }
}
