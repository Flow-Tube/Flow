package io.github.aedev.flow.ui.components.layout.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBarDefaults
import androidx.compose.material3.WideNavigationRailDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.ui.utils.LocalWindowSizeClass
import io.github.aedev.flow.ui.utils.isExpandedWidth
import io.github.aedev.flow.ui.utils.isMediumHeight
import kotlin.math.roundToInt

const val FLOW_NAV_BAR_TAG = "flow_nav_bar"
const val FLOW_NAV_RAIL_TAG = "flow_nav_rail"

object FlowNavigationDefaults {
    /**
     * The bar's height above the system navigation inset: Material's short navigation bar
     * container. The bar reports its real height, which grows past this at large font scales.
     */
    val BarHeight: Dp = 64.dp
}

/**
 * Whether this window is wide enough to navigate from the start edge instead of the bottom. The
 * decision belongs to the window's size class, so a phone in a narrow split window keeps the bar
 * even on a large display.
 */
@Composable
fun flowUsesNavigationRail(): Boolean = LocalWindowSizeClass.current.let { it.isExpandedWidth && it.isMediumHeight }

/**
 * The app's primary navigation around [content]: the bottom bar over it on compact and medium
 * windows, the wide navigation rail beside it once the window is expanded.
 *
 * The bar overlays the content, so callers reserve [onBarHeightChanged] themselves. [barScrollState]
 * moves it with the content's scroll. The rail takes
 * its own column; [onRailWidthChanged] is only for overlays drawn outside this layout.
 */
@Composable
fun FlowNavigationChrome(
    tabs: List<FlowTab>,
    selectedTab: FlowTab?,
    onTabSelected: (FlowTab) -> Unit,
    barVisible: Boolean,
    railVisible: Boolean,
    modifier: Modifier = Modifier,
    barScrollState: FlowNavigationScrollState? = null,
    onBarHeightChanged: (Dp) -> Unit = {},
    onRailWidthChanged: (Dp) -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val usesRail = flowUsesNavigationRail()
    val showRail = usesRail && railVisible
    val density = LocalDensity.current
    val railInsets = WideNavigationRailDefaults.windowInsets

    Row(modifier = modifier.fillMaxSize()) {
        if (showRail) {
            FlowNavigationRail(
                tabs = tabs,
                selectedTab = selectedTab,
                onTabSelected = onTabSelected,
                modifier =
                    Modifier
                        .testTag(FLOW_NAV_RAIL_TAG)
                        .onSizeChanged { onRailWidthChanged(with(density) { it.width.toDp() }) },
            )
        }
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(
                        if (showRail) {
                            Modifier.consumeWindowInsets(railInsets.only(WindowInsetsSides.Start))
                        } else {
                            Modifier
                        },
                    ),
        ) {
            content()
            if (!usesRail) {
                NavigationBarSlot(
                    tabs = tabs,
                    selectedTab = selectedTab,
                    onTabSelected = onTabSelected,
                    visible = barVisible,
                    scrollState = barScrollState,
                    onHeightChanged = onBarHeightChanged,
                )
            }
        }
    }
}

@Composable
private fun BoxScope.NavigationBarSlot(
    tabs: List<FlowTab>,
    selectedTab: FlowTab?,
    onTabSelected: (FlowTab) -> Unit,
    visible: Boolean,
    scrollState: FlowNavigationScrollState?,
    onHeightChanged: (Dp) -> Unit,
) {
    val density = LocalDensity.current
    val bottomInsetPx = ShortNavigationBarDefaults.windowInsets.getBottom(density)
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically(motion.defaultSpatialSpec()) { it } + fadeIn(motion.defaultEffectsSpec()),
        exit = slideOutVertically(motion.fastSpatialSpec()) { it } + fadeOut(motion.fastEffectsSpec()),
    ) {
        FlowNavigationBar(
            tabs = tabs,
            selectedTab = selectedTab,
            onTabSelected = onTabSelected,
            modifier =
                Modifier
                    .testTag(FLOW_NAV_BAR_TAG)
                    .onSizeChanged {
                        onHeightChanged(with(density) { (it.height - bottomInsetPx).toDp() })
                        scrollState?.setBarHeight(it.height.toFloat())
                    }.offset { IntOffset(0, scrollState?.offsetPx?.roundToInt() ?: 0) },
        )
    }
}
