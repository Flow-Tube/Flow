package io.github.aedev.flow.ui.components.shared

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.ui.components.layout.LocalFlowBottomInsets
import io.github.aedev.flow.ui.components.layout.floatAboveBottomChrome

/** One button in a select mode's toolbar. */
class FlowSelectionAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
)

/**
 * The actions for what is selected in a list, floating over its bottom edge so they stay
 * under the thumb and don't depend on how wide the top bar is.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FlowSelectionToolbar(
    visible: Boolean,
    summary: String,
    actions: List<FlowSelectionAction>,
    modifier: Modifier = Modifier,
) {
    val bottomInsets = LocalFlowBottomInsets.current
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = FloatingToolbarDefaults.verticalEnterTransition(Alignment.Bottom),
        exit = FloatingToolbarDefaults.verticalExitTransition(Alignment.Bottom),
    ) {
        // Inverse, like a snackbar: the standard container is the page's surface and the vibrant one
        // matches a selected row in several palettes, so either would melt into the list under it.
        HorizontalFloatingToolbar(
            expanded = true,
            colors =
                FloatingToolbarDefaults.standardFloatingToolbarColors(
                    toolbarContainerColor = MaterialTheme.colorScheme.inverseSurface,
                    toolbarContentColor = MaterialTheme.colorScheme.inverseOnSurface,
                ),
            // Above the bar, the music mini player and the gesture area, riding along as they move. A remote
            // could reach it only from the list's last row, so on a D-pad focus starts here when it opens.
            modifier =
                Modifier
                    .then(dpadFocusOnEnter())
                    .floatAboveBottomChrome(bottomInsets)
                    .padding(bottom = FloatingToolbarDefaults.ScreenOffset),
            leadingContent = {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            },
        ) {
            actions.forEach { action ->
                IconButton(onClick = action.onClick) {
                    Icon(
                        imageVector = action.icon,
                        contentDescription = action.label,
                        tint = LocalContentColor.current,
                    )
                }
            }
        }
    }
}
