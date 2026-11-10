package io.github.aedev.flow.ui.components.musicplayer.queue

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.PlaylistRemove
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import coil3.compose.AsyncImage
import io.github.aedev.flow.R
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionsDefaults
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionsGroup
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionsHeader
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionsSheet
import io.github.aedev.flow.ui.components.shared.quickactions.actionRow

/** A queue row's actions as a menu, for the remote's Menu key or a held OK. */
@Composable
internal fun QueueRowMenuSheet(
    track: MusicTrack,
    actions: List<QueueRowMenuAction>,
    onAction: (QueueRowMenuAction) -> Unit,
    onDismiss: () -> Unit,
) {
    QuickActionsSheet(onDismiss = onDismiss) { sheet ->
        QuickActionsHeader(title = track.title, subtitle = track.artist) {
            AsyncImage(
                model = track.listThumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .size(QuickActionsDefaults.SquareArtworkSize)
                        .clip(MaterialTheme.shapes.medium),
            )
        }
        QuickActionsGroup(
            title = null,
            rows =
                actions.map { action ->
                    actionRow(
                        key = action.name,
                        icon = action.icon,
                        title = stringResource(action.labelRes),
                        destructive = action == QueueRowMenuAction.REMOVE,
                    ) {
                        onAction(action)
                        sheet.close()
                    }
                },
        )
    }
}

private val QueueRowMenuAction.icon: ImageVector
    get() =
        when (this) {
            QueueRowMenuAction.PLAY_NEXT -> Icons.AutoMirrored.Outlined.PlaylistPlay
            QueueRowMenuAction.ADD_TO_QUEUE, QueueRowMenuAction.MOVE_TO_END -> Icons.AutoMirrored.Outlined.QueueMusic
            QueueRowMenuAction.MOVE_UP -> Icons.Rounded.KeyboardArrowUp
            QueueRowMenuAction.MOVE_DOWN -> Icons.Rounded.KeyboardArrowDown
            QueueRowMenuAction.REMOVE -> Icons.Outlined.PlaylistRemove
        }

private val QueueRowMenuAction.labelRes: Int
    get() =
        when (this) {
            QueueRowMenuAction.PLAY_NEXT -> R.string.play_next
            QueueRowMenuAction.ADD_TO_QUEUE -> R.string.add_to_queue
            QueueRowMenuAction.MOVE_UP -> R.string.move_up
            QueueRowMenuAction.MOVE_DOWN -> R.string.move_down
            QueueRowMenuAction.MOVE_TO_END -> R.string.queue_move_to_end
            QueueRowMenuAction.REMOVE -> R.string.remove_from_queue
        }
