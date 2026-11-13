package io.github.aedev.flow.ui.components.library

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlaylistRemove
import androidx.compose.material.icons.outlined.VerticalAlignBottom
import androidx.compose.material.icons.outlined.VerticalAlignTop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.PlaylistSwipeAction
import io.github.aedev.flow.data.local.PlaylistSwipes
import kotlinx.coroutines.launch

private val SwipeLabelPadding = 24.dp
private val SwipeLabelSpacing = 8.dp

@get:StringRes
internal val PlaylistSwipeAction.labelRes: Int
    get() =
        when (this) {
            PlaylistSwipeAction.NONE -> R.string.off
            PlaylistSwipeAction.MOVE_TO_TOP -> R.string.playlist_move_to_top
            PlaylistSwipeAction.MOVE_TO_BOTTOM -> R.string.playlist_move_to_bottom
            PlaylistSwipeAction.REMOVE -> R.string.remove_from_playlist_action
        }

/**
 * A playlist video that runs the viewer's swipe choices. A move springs back into place; a removed
 * video leaves with the swipe, and the host's Undo brings it back. A direction whose action isn't
 * offered (null in [actionFor]) doesn't swipe.
 */
@Composable
internal fun PlaylistVideoSwipeBox(
    swipes: PlaylistSwipes,
    enabled: Boolean,
    removeLabel: String,
    actionFor: (PlaylistSwipeAction) -> (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // The settings name physical sides; the box names them by reading direction.
    val towardsStart = if (rtl) swipes.right else swipes.left
    val towardsEnd = if (rtl) swipes.left else swipes.right
    val startAction = actionFor(towardsStart)
    val endAction = actionFor(towardsEnd)
    val scope = rememberCoroutineScope()
    // Not rememberSaveable: the list restores saved state by key, so a video brought back by Undo
    // would return already swiped away.
    val positionalThreshold = SwipeToDismissBoxDefaults.positionalThreshold
    val state = remember { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, positionalThreshold) }
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = endAction != null,
        enableDismissFromEndToStart = startAction != null,
        gesturesEnabled = enabled && (startAction != null || endAction != null),
        onDismiss = { value ->
            val toEnd = value == SwipeToDismissBoxValue.StartToEnd
            (if (toEnd) endAction else startAction)?.invoke()
            if ((if (toEnd) towardsEnd else towardsStart) != PlaylistSwipeAction.REMOVE) scope.launch { state.reset() }
        },
        backgroundContent = {
            val action =
                when (state.dismissDirection) {
                    SwipeToDismissBoxValue.StartToEnd -> towardsEnd
                    SwipeToDismissBoxValue.EndToStart -> towardsStart
                    SwipeToDismissBoxValue.Settled -> return@SwipeToDismissBox
                }
            SwipeBackground(
                action = action,
                label = if (action == PlaylistSwipeAction.REMOVE) removeLabel else action.labelText(),
                alignEnd = state.dismissDirection == SwipeToDismissBoxValue.EndToStart,
            )
        },
    ) {
        // The rows draw no background of their own, so the page colour keeps the one underneath hidden.
        Box(Modifier.background(MaterialTheme.colorScheme.background)) { content() }
    }
}

@Composable
private fun PlaylistSwipeAction.labelText(): String = stringResource(labelRes)

@Composable
private fun SwipeBackground(
    action: PlaylistSwipeAction,
    label: String,
    alignEnd: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val remove = action == PlaylistSwipeAction.REMOVE
    val container = if (remove) colors.errorContainer else colors.secondaryContainer
    val onContainer = if (remove) colors.onErrorContainer else colors.onSecondaryContainer
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(container)
                .padding(horizontal = SwipeLabelPadding),
        contentAlignment = if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SwipeLabelSpacing)) {
            action.icon?.let { Icon(it, contentDescription = null, tint = onContainer) }
            Text(text = label, style = MaterialTheme.typography.labelLarge, color = onContainer)
        }
    }
}

private val PlaylistSwipeAction.icon: ImageVector?
    get() =
        when (this) {
            PlaylistSwipeAction.NONE -> null
            PlaylistSwipeAction.MOVE_TO_TOP -> Icons.Outlined.VerticalAlignTop
            PlaylistSwipeAction.MOVE_TO_BOTTOM -> Icons.Outlined.VerticalAlignBottom
            PlaylistSwipeAction.REMOVE -> Icons.Outlined.PlaylistRemove
        }
