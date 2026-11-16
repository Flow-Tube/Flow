package io.github.aedev.flow.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R

private val NewVideosTopPadding = 8.dp

/**
 * Shown while a newer feed waits behind the cached cards of a launch (#1447). A sideways swipe puts
 * it away for that feed (#1913); the held feed still lands at the end of the cached cards or on a pull.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun HomeNewVideosButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var dismissed by remember(visible) { mutableStateOf(false) }
    val positionalThreshold = SwipeToDismissBoxDefaults.positionalThreshold
    val swipeState = remember(visible) { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, positionalThreshold) }
    val dismissLabel = stringResource(R.string.dismiss)
    AnimatedVisibility(
        visible = visible && !dismissed,
        enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) + scaleIn(MaterialTheme.motionScheme.defaultSpatialSpec()),
        exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
        modifier = modifier.padding(top = NewVideosTopPadding),
    ) {
        SwipeToDismissBox(
            state = swipeState,
            backgroundContent = {},
            onDismiss = { dismissed = true },
            modifier =
                Modifier.semantics {
                    customActions =
                        listOf(
                            CustomAccessibilityAction(dismissLabel) {
                                dismissed = true
                                true
                            },
                        )
                },
        ) {
            Button(
                onClick = onClick,
                contentPadding = ButtonDefaults.ExtraSmallContentPadding,
                modifier = Modifier.heightIn(min = ButtonDefaults.ExtraSmallContainerHeight),
            ) {
                Icon(
                    imageVector = Icons.Rounded.ArrowUpward,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.ExtraSmallIconSize),
                )
                Spacer(Modifier.width(ButtonDefaults.ExtraSmallIconSpacing))
                Text(text = stringResource(R.string.home_new_videos))
            }
        }
    }
}
