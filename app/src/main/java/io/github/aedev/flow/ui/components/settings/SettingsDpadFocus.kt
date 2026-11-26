package io.github.aedev.flow.ui.components.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import io.github.aedev.flow.ui.components.shared.LocalDpadNavigation

/**
 * Makes a row or card that does nothing on touch one D-pad focus stop, with the focus ring in [shape]:
 * a remote scrolls a page only by moving focus, so a page of such blocks could not be read past the
 * first. [onClick], when given, is what OK does there. A no-op unless [LocalDpadNavigation] is on.
 */
@Composable
fun Modifier.dpadFocusStop(
    shape: Shape,
    onClick: (() -> Unit)? = null,
): Modifier {
    if (!LocalDpadNavigation.current) return this
    val interactionSource = remember { MutableInteractionSource() }
    val ring = ripple(focusRingShape = shape)
    return if (onClick != null) {
        clickable(interactionSource = interactionSource, indication = ring, onClick = onClick)
    } else {
        indication(interactionSource, ring).focusable(interactionSource = interactionSource)
    }
}
