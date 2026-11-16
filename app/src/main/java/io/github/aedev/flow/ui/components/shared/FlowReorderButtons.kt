package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R

/**
 * Move up and Move down for a row of a list that reorders by dragging, which a remote can't do. Lists
 * show these in place of the drag handle when [LocalDpadNavigation] is on. The row keeps its key, so
 * the focused button moves with it. A null action, at either end of the list, leaves its button doing
 * nothing rather than disabled: a button that disables itself while focused drops focus out of the list.
 */
@Composable
fun FlowReorderButtons(
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onMoveUp?.invoke() }) {
            Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = stringResource(R.string.move_up))
        }
        IconButton(onClick = { onMoveDown?.invoke() }) {
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.move_down))
        }
    }
}
