package io.github.aedev.flow.ui.components.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R

private val IconPadding = 24.dp

/**
 * A finished download that deletes when swiped toward its end edge. TalkBack gets Delete as a
 * custom action, since the swipe is not reachable from a screen reader. Off in selection mode.
 */
@Composable
internal fun DownloadSwipeToDelete(
    enabled: Boolean,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // Not rememberSaveable: the grid restores saved state by key, so a download brought back by
    // Undo would return already swiped away and delete itself again.
    val positionalThreshold = SwipeToDismissBoxDefaults.positionalThreshold
    val state = remember { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, positionalThreshold) }
    val deleteLabel = stringResource(R.string.delete)
    SwipeToDismissBox(
        state = state,
        modifier =
            modifier.clip(MaterialTheme.shapes.large).semantics {
                if (enabled) {
                    customActions =
                        listOf(
                            CustomAccessibilityAction(deleteLabel) {
                                onDelete()
                                true
                            },
                        )
                }
            },
        enableDismissFromStartToEnd = false,
        gesturesEnabled = enabled,
        onDismiss = { value -> if (value == SwipeToDismissBoxValue.EndToStart) onDelete() },
        backgroundContent = {
            if (state.dismissDirection != SwipeToDismissBoxValue.EndToStart) return@SwipeToDismissBox
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = IconPadding),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) {
        // The rows draw no background of their own, so the page colour keeps the red one hidden under them.
        Box(Modifier.background(MaterialTheme.colorScheme.background)) { content() }
    }
}
