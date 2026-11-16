package io.github.aedev.flow.ui.screens.settings.appearance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.CallToAction
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.Swipe
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material.icons.outlined.WebAsset
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.NavBarLabels
import io.github.aedev.flow.data.local.NavBarScroll
import io.github.aedev.flow.data.local.NavBarStyle
import io.github.aedev.flow.ui.components.layout.navigation.resolveDefaultFlowTab
import io.github.aedev.flow.ui.components.layout.navigation.visibleFlowTabs
import io.github.aedev.flow.ui.components.settings.SettingsPage
import io.github.aedev.flow.ui.components.settings.switch
import io.github.aedev.flow.ui.components.settings.toggleGroup
import io.github.aedev.flow.ui.components.shared.FlowToggleOption
import io.github.aedev.flow.ui.screens.settings.index.NavigationBarIndex

/** Which tabs the navigation bar shows, in what order, and which one Flow opens on. */
@Composable
internal fun NavigationBarScreen(
    onBack: (() -> Unit)?,
    highlight: String?,
    viewModel: AppearanceViewModel = hiltViewModel(),
) {
    val visibility by viewModel.navigationVisibility.collectAsStateWithLifecycle()
    val shortsContent by viewModel.shortsContent.collectAsStateWithLifecycle()
    val hideOnScroll by viewModel.hideNavOnScroll.collectAsStateWithLifecycle()
    val labels by viewModel.navLabels.collectAsStateWithLifecycle()
    val style by viewModel.navStyle.collectAsStateWithLifecycle()
    val scrub by viewModel.navScrub.collectAsStateWithLifecycle()
    val iconPop by viewModel.navIconPop.collectAsStateWithLifecycle()
    val scroll by viewModel.navScroll.collectAsStateWithLifecycle()
    val searchShowsBar by viewModel.searchShowsNavBar.collectAsStateWithLifecycle()
    val innerPagesShowBar by viewModel.innerPagesShowNavBar.collectAsStateWithLifecycle()
    val order by viewModel.navTabOrder.collectAsStateWithLifecycle()
    val defaultIndex by viewModel.defaultNavTabIndex.collectAsStateWithLifecycle()

    val effectiveVisibility = visibility.copy(shorts = visibility.shorts && shortsContent)
    val visibleTabs = visibleFlowTabs(order, effectiveVisibility)
    val defaultTab = resolveDefaultFlowTab(defaultIndex, order, effectiveVisibility)
    val shortsHiddenSummary = stringResource(R.string.settings_shorts_tab_disabled_summary)
    val styleOptions =
        listOf(
            FlowToggleOption(NavBarStyle.STANDARD, stringResource(R.string.settings_nav_style_standard)),
            FlowToggleOption(NavBarStyle.FLOATING, stringResource(R.string.settings_nav_style_floating)),
        )
    val labelOptions =
        listOf(
            FlowToggleOption(NavBarLabels.ALWAYS, stringResource(R.string.settings_nav_labels_always)),
            FlowToggleOption(NavBarLabels.SELECTED, stringResource(R.string.settings_nav_labels_selected)),
            FlowToggleOption(NavBarLabels.NEVER, stringResource(R.string.settings_nav_labels_never)),
        )

    val scrollOptions =
        listOf(
            FlowToggleOption(NavBarScroll.HIDE, stringResource(R.string.settings_nav_scroll_hide)),
            FlowToggleOption(NavBarScroll.SHRINK, stringResource(R.string.settings_nav_scroll_shrink)),
        )

    SettingsPage(
        title = stringResource(R.string.settings_navigation_bar_title),
        onBack = onBack,
        highlight = highlight,
    ) {
        group(key = "navigation_bar.style", header = R.string.settings_section_style) {
            toggleGroup(NavigationBarIndex.style, styleOptions, style, viewModel::setNavStyle)
            toggleGroup(NavigationBarIndex.labels, labelOptions, labels, viewModel::setNavLabels)
            if (style == NavBarStyle.FLOATING) {
                toggleGroup(
                    NavigationBarIndex.scroll,
                    scrollOptions,
                    scroll,
                    viewModel::setNavScroll,
                    enabled = hideOnScroll,
                )
                switch(NavigationBarIndex.scrub, scrub, viewModel::setNavScrub, icon = Icons.Outlined.Swipe)
                switch(NavigationBarIndex.iconPop, iconPop, viewModel::setNavIconPop, icon = Icons.Outlined.Animation)
            }
        }
        group(key = "navigation_bar.tabs", header = R.string.settings_section_tabs) {
            switch(NavigationBarIndex.home, visibility.home, viewModel::setHomeTab, icon = Icons.Outlined.Home)
            switch(
                NavigationBarIndex.shorts,
                checked = visibility.shorts && shortsContent,
                onCheckedChange = viewModel::setShortsTab,
                enabled = shortsContent,
                summary = if (shortsContent) null else shortsHiddenSummary,
                icon = Icons.Outlined.Slideshow,
            )
            switch(NavigationBarIndex.music, visibility.music, viewModel::setMusicTab, icon = Icons.Outlined.MusicNote)
            switch(NavigationBarIndex.search, visibility.search, viewModel::setSearchTab, icon = Icons.Outlined.Search)
            switch(NavigationBarIndex.explore, visibility.categories, viewModel::setExploreTab, icon = Icons.Outlined.Explore)
            switch(NavigationBarIndex.downloads, visibility.downloads, viewModel::setDownloadsTab, icon = Icons.Outlined.Download)
            switch(NavigationBarIndex.hideOnScroll, hideOnScroll, viewModel::setHideNavOnScroll, icon = Icons.Outlined.UnfoldLess)
            switch(NavigationBarIndex.showOnSearch, searchShowsBar, viewModel::setSearchShowsNavBar, icon = Icons.Outlined.CallToAction)
            switch(
                NavigationBarIndex.showOnOtherPages,
                innerPagesShowBar,
                viewModel::setInnerPagesShowNavBar,
                icon = Icons.Outlined.WebAsset,
            )
        }
        group(
            key = NavigationBarIndex.order.key,
            header = R.string.settings_section_tab_order,
            footer = R.string.content_settings_nav_order_subtitle,
        ) {
            row("navigation_bar.order.list") { shape ->
                NavTabOrderList(
                    order = order,
                    enabledTabs = visibleTabs.toSet(),
                    defaultTab = defaultTab,
                    onOrderChanged = viewModel::setNavTabOrder,
                    onDefaultSelected = { viewModel.setDefaultNavTab(it.id) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                )
            }
        }
    }
}
