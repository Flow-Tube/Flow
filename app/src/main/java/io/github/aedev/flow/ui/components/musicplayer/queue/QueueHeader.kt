package io.github.aedev.flow.ui.components.musicplayer.queue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.aedev.flow.R
import io.github.aedev.flow.player.RepeatMode

/** Title, track count, Select, and the shuffle and repeat pair shared by the sheet and the side pane. */
@Composable
internal fun QueueHeader(
    trackCount: Int,
    shuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    onShuffleQueue: () -> Unit,
    onCycleRepeat: () -> Unit,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = 22.dp, end = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.up_next),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = pluralStringResource(R.plurals.queue_track_count, trackCount, trackCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onSelect) {
            Icon(Icons.Rounded.Checklist, contentDescription = stringResource(R.string.select_songs))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FilledTonalIconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onShuffleQueue()
                },
                modifier = Modifier.size(width = 52.dp, height = 42.dp),
                shape =
                    RoundedCornerShape(
                        topStart = 21.dp,
                        bottomStart = 21.dp,
                        topEnd = 6.dp,
                        bottomEnd = 6.dp,
                    ),
                colors =
                    IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor =
                            if (shuffleEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            },
                        contentColor =
                            if (shuffleEnabled) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            },
                    ),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Shuffle,
                    contentDescription = stringResource(R.string.shuffle),
                    modifier = Modifier.size(20.dp),
                )
            }
            val repeatActive = repeatMode != RepeatMode.OFF
            FilledTonalIconButton(
                onClick = onCycleRepeat,
                modifier = Modifier.size(width = 52.dp, height = 42.dp),
                shape =
                    RoundedCornerShape(
                        topStart = 6.dp,
                        bottomStart = 6.dp,
                        topEnd = 21.dp,
                        bottomEnd = 21.dp,
                    ),
                colors =
                    IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor =
                            if (repeatActive) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            },
                        contentColor =
                            if (repeatActive) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f)
                            },
                    ),
            ) {
                Icon(
                    imageVector =
                        if (repeatMode == RepeatMode.ONE) {
                            Icons.Rounded.RepeatOne
                        } else {
                            Icons.Rounded.Repeat
                        },
                    contentDescription = stringResource(R.string.repeat),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
