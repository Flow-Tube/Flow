package io.github.aedev.flow.ui.components.layout.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal val FloatingNavItemHeight: Dp = 48.dp
internal val FloatingNavNamedBelowHeight: Dp = 56.dp
internal val FloatingNavBelowIndicatorHeight: Dp = 32.dp
private val NameBesideMinStart = 12.dp
private val NameBelowIconTop = 4.dp
private val NameBelowGap = 4.dp
private const val PRESSED_SCALE = 0.92f
private const val POP_VELOCITY = 4f

/**
 * One slot of the floating bar. Its width comes from [width], read in layout only, so the bar can spring
 * every slot's width without recomposing. The name fades in as the open slot grows to [namedWidth].
 */
@Composable
internal fun FloatingNavItem(
    icon: ImageVector,
    name: String,
    selected: Boolean,
    names: FloatingNavNames,
    width: State<Float>,
    namedWidth: Float,
    contentColor: Color,
    pop: Boolean,
    hidden: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = MaterialTheme.motionScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) PRESSED_SCALE else 1f,
        animationSpec = if (pressed) motion.fastEffectsSpec() else motion.fastSpatialSpec(),
        label = "navItemPress",
    )
    val popScale = remember { Animatable(1f) }
    val firstSelection = remember { booleanArrayOf(true) }
    LaunchedEffect(selected) {
        val initial = firstSelection[0]
        firstSelection[0] = false
        if (selected && pop && !initial) popScale.animateTo(1f, motion.fastSpatialSpec(), initialVelocity = POP_VELOCITY)
    }
    val iconItem = FloatingNavItemHeight
    val showsName = names == FloatingNavNames.BELOW || (names == FloatingNavNames.BESIDE_OPEN && selected)
    val description = if (showsName) null else name

    Layout(
        content = {
            Icon(
                imageVector = icon,
                contentDescription = description,
                tint = contentColor,
                modifier =
                    Modifier.graphicsLayer {
                        scaleX = popScale.value
                        scaleY = popScale.value
                    },
            )
            if (names != FloatingNavNames.NONE) {
                Text(
                    text = name,
                    color = contentColor,
                    style =
                        if (names ==
                            FloatingNavNames.BELOW
                        ) {
                            MaterialTheme.typography.labelMedium
                        } else {
                            MaterialTheme.typography.labelLarge
                        },
                    maxLines = 1,
                    softWrap = false,
                    overflow = if (names == FloatingNavNames.BELOW) TextOverflow.Ellipsis else TextOverflow.Clip,
                )
            }
        },
        modifier =
            modifier
                .layout { measurable, _ ->
                    val w = width.value.roundToInt().coerceAtLeast(0)
                    val h = (if (names == FloatingNavNames.BELOW) FloatingNavNamedBelowHeight else iconItem).roundToPx()
                    val placeable = measurable.measure(Constraints.fixed(w, h))
                    layout(w, h) {
                        placeable.placeWithLayer(0, 0) {
                            alpha = (width.value / iconItem.toPx()).coerceIn(0f, 1f)
                            scaleX = pressScale
                            scaleY = pressScale
                        }
                    }
                }.clip(CircleShape)
                .then(if (hidden) Modifier.clearAndSetSemantics {} else Modifier)
                .selectable(
                    selected = selected,
                    enabled = !hidden,
                    onClick = onClick,
                    role = Role.Tab,
                    interactionSource = interaction,
                    indication = ripple(),
                ),
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val iconPlaceable = measurables[0].measure(Constraints())
        val namePlaceable =
            measurables.getOrNull(1)?.measure(
                if (names ==
                    FloatingNavNames.BELOW
                ) {
                    Constraints(maxWidth = w)
                } else {
                    Constraints()
                },
            )
        layout(w, h) {
            when {
                names == FloatingNavNames.BELOW && namePlaceable != null -> {
                    val iconTop = NameBelowIconTop.roundToPx() + (FloatingNavBelowIndicatorHeight.roundToPx() - iconPlaceable.height) / 2
                    iconPlaceable.placeRelative((w - iconPlaceable.width) / 2, iconTop)
                    namePlaceable.placeRelative(
                        (w - namePlaceable.width) / 2,
                        NameBelowIconTop.roundToPx() + FloatingNavBelowIndicatorHeight.roundToPx() + NameBelowGap.roundToPx(),
                    )
                }

                showsName && namePlaceable != null -> {
                    val gap = 8.dp.roundToPx()
                    val content = iconPlaceable.width + gap + namePlaceable.width
                    val start = maxOf(NameBesideMinStart.roundToPx(), (w - content) / 2)
                    iconPlaceable.placeRelative(start, (h - iconPlaceable.height) / 2)
                    val iconOnly = iconItem.toPx()
                    namePlaceable.placeRelativeWithLayer(start + iconPlaceable.width + gap, (h - namePlaceable.height) / 2) {
                        alpha = ((width.value - iconOnly) / (namedWidth - iconOnly).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    }
                }

                else -> {
                    iconPlaceable.placeRelative((w - iconPlaceable.width) / 2, (h - iconPlaceable.height) / 2)
                }
            }
        }
    }
}

/** The fixed sizes of the floating bar's slots. */
@Composable
internal fun rememberFloatingNavMetrics(): FloatingNavMetrics {
    val density = androidx.compose.ui.platform.LocalDensity.current
    return remember(density) {
        with(density) {
            FloatingNavMetrics(
                iconItem = FloatingNavItemHeight.toPx(),
                icon = 24.dp.toPx(),
                labelledStart = 16.dp.toPx(),
                iconLabelGap = 8.dp.toPx(),
                labelledEnd = 20.dp.toPx(),
                namedItemMin = 64.dp.toPx(),
                namedItemPadding = 16.dp.toPx(),
                plainItem = 56.dp.toPx(),
                gap = 2.dp.toPx(),
                belowIndicatorWidth = 56.dp.toPx(),
            )
        }
    }
}
