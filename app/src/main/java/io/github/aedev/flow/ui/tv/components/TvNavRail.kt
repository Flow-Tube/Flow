package io.github.aedev.flow.ui.tv.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailDefaults
import androidx.compose.material3.WideNavigationRailItemDefaults
import androidx.compose.material3.WideNavigationRailValue
import androidx.compose.material3.rememberWideNavigationRailState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.layout.navigation.FlowTab
import io.github.aedev.flow.ui.components.layout.navigation.icon
import io.github.aedev.flow.ui.tv.navigation.TV_SETTINGS_ROUTE

/**
 * The TV navigation rail: M3's wide rail with the user's tabs and Settings at its foot. It expands
 * with labels while it holds focus and lies over the content, which is laid out against the collapsed
 * width, so expanding never moves the page.
 *
 * Focus enters on the selected destination when it comes from the screen edge the rail sits on (a
 * D-pad press, or the shell's Back); that edge is the right one in a right-to-left language. Any other entry is Compose handing out initial focus after the focused item went away, which
 * would otherwise land here as the first item on screen, so it goes to [onOtherEntry] when that takes it.
 */
@Composable
fun TvNavRail(
    tabs: List<FlowTab>,
    selectedRoute: String?,
    onSelect: (String) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    focusRequester: FocusRequester,
    onOtherEntry: () -> Boolean,
    modifier: Modifier = Modifier,
    nowPlayingTitle: String? = null,
    onOpenNowPlaying: () -> Unit = {},
    profile: @Composable (expanded: Boolean) -> Unit,
) {
    val state = rememberWideNavigationRailState()
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(hasFocus) {
        if (hasFocus) state.expand() else state.collapse()
    }
    val expanded = state.targetValue == WideNavigationRailValue.Expanded
    val selectedRequester = remember { FocusRequester() }
    val towardRail = railFocusDirection(LocalLayoutDirection.current)
    val colors = MaterialTheme.colorScheme

    WideNavigationRail(
        modifier =
            modifier
                .fillMaxHeight()
                .onFocusChanged {
                    hasFocus = it.hasFocus
                    onFocusChanged(it.hasFocus)
                }.focusRequester(focusRequester)
                .focusProperties {
                    onEnter = {
                        val redirected = requestedFocusDirection != towardRail && onOtherEntry()
                        if (!redirected) selectedRequester.requestFocus()
                    }
                    onExit = { if (requestedFocusDirection == towardRail) cancelFocusChange() }
                }.focusGroup(),
        state = state,
        colors = WideNavigationRailDefaults.colors(containerColor = if (expanded) colors.surfaceContainer else colors.background),
        header = { profile(expanded) },
        arrangement = LastItemAtBottom,
    ) {
        tabs.forEach { tab ->
            val selected = tab.route == selectedRoute
            TvRailItem(
                selected = selected,
                icon = tab.icon(selected),
                label = stringResource(tab.labelRes),
                expanded = expanded,
                onClick = { onSelect(tab.route) },
                modifier = if (selected) Modifier.focusRequester(selectedRequester) else Modifier,
            )
        }
        // Music keeps playing under the browse screens; this is the one stop away from its full player.
        nowPlayingTitle?.let { title ->
            TvRailItem(
                selected = false,
                icon = Icons.Rounded.GraphicEq,
                label = title,
                expanded = expanded,
                onClick = onOpenNowPlaying,
            )
        }
        val settingsSelected = selectedRoute == TV_SETTINGS_ROUTE
        TvRailItem(
            selected = settingsSelected,
            icon = if (settingsSelected) Icons.Filled.Settings else Icons.Outlined.Settings,
            label = stringResource(R.string.settings),
            expanded = expanded,
            onClick = { onSelect(TV_SETTINGS_ROUTE) },
            modifier = if (settingsSelected) Modifier.focusRequester(selectedRequester) else Modifier,
        )
    }
}

/**
 * One destination on [TvNavRail], in M3's rail item colours. M3's own rail item draws its ripple on
 * the indicator and passes it presses alone, so it never shows focus; a Surface draws the theme's
 * focus ring on its pill shape instead. Collapsed it shows the icon, expanded the label beside it.
 */
@Composable
private fun TvRailItem(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WideNavigationRailItemDefaults.colors()
    val motion = MaterialTheme.motionScheme
    Surface(
        selected = selected,
        onClick = onClick,
        modifier = modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        shape = CircleShape,
        color = if (selected) colors.selectedIndicatorColor else Color.Transparent,
        contentColor = if (selected) colors.selectedIconColor else colors.unselectedIconColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = icon, contentDescription = label)
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(motion.defaultEffectsSpec()) + expandHorizontally(motion.defaultSpatialSpec()),
                exit = fadeOut(motion.fastEffectsSpec()) + shrinkHorizontally(motion.fastSpatialSpec()),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) colors.selectedTextColor else colors.unselectedTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        Modifier
                            .widthIn(max = RailLabelMaxWidth)
                            .padding(start = 12.dp, end = 8.dp),
                )
            }
        }
    }
}

/** Keeps a long song title from widening the open rail past M3's wide-rail width. */
private val RailLabelMaxWidth = 200.dp

/** The tabs from the top, and the last item (Settings) on the rail's floor, as Google's TV rail places it. */
private object LastItemAtBottom : Arrangement.Vertical {
    override fun Density.arrange(
        totalSize: Int,
        sizes: IntArray,
        outPositions: IntArray,
    ) {
        var top = 0
        sizes.forEachIndexed { index, size ->
            if (index == sizes.lastIndex && index > 0) {
                outPositions[index] = (totalSize - size).coerceAtLeast(top)
            } else {
                outPositions[index] = top
                top += size
            }
        }
    }
}

/** The D-pad direction that points from the content toward the rail, which sits at the start edge. */
fun railFocusDirection(layoutDirection: LayoutDirection): FocusDirection =
    if (layoutDirection == LayoutDirection.Rtl) FocusDirection.Right else FocusDirection.Left
