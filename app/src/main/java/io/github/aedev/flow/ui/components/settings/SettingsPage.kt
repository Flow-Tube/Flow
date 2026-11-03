package io.github.aedev.flow.ui.components.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.ui.components.layout.LocalFlowBottomInsets
import io.github.aedev.flow.ui.components.layout.floatAboveBottomChrome
import io.github.aedev.flow.ui.components.layout.flowBottomContentPadding
import io.github.aedev.flow.ui.components.layout.topbar.FlowGlobalActionsMode
import io.github.aedev.flow.ui.components.layout.topbar.FlowTopBar
import io.github.aedev.flow.ui.components.shared.FlowMaxContentWidth
import io.github.aedev.flow.ui.components.shared.LocalDpadNavigation
import kotlinx.coroutines.flow.first

internal val SettingsMaxContentWidth = FlowMaxContentWidth
internal val SettingsHorizontalPadding = 16.dp
private val SettingsBottomSpacing = 32.dp
private const val HIGHLIGHT_VISIBLE_OFFSET_PX = -160

/** The key of the option a search result asked this page to scroll to and flash. */
internal val LocalSettingsHighlight = staticCompositionLocalOf<String?> { null }

/** The keys of the options and page rows marked as new on this device (#1676). */
internal val LocalNewSettings = staticCompositionLocalOf<Set<String>> { emptySet() }

/**
 * The frame every settings page is built in: the unified top bar, one lazy column capped at
 * [SettingsMaxContentWidth], and scroll-to-highlight for search results.
 *
 * Pass [onBack] as null when the page is the root of the detail pane beside the settings list,
 * where there is nothing to go back to.
 *
 * @param header content pinned under the top bar, such as the toggle group that switches a page's
 *   views.
 */
@Composable
fun SettingsPage(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    highlight: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    header: (@Composable () -> Unit)? = null,
    snackbarHostState: SnackbarHostState? = null,
    listState: LazyListState = rememberLazyListState(),
    content: SettingsListScope.() -> Unit,
) {
    val keys = remember { SettingsKeyRecorder() }

    LaunchedEffect(highlight) {
        if (highlight == null) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
        val index = keys.indexOf(highlight)
        if (index >= 0) listState.animateScrollToItem(index, HIGHLIGHT_VISIBLE_OFFSET_PX)
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0.dp),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            FlowTopBar(
                title = title,
                subtitle = subtitle,
                onBack = onBack,
                actions = actions,
                globalActions = FlowGlobalActionsMode.None,
            )
        },
        snackbarHost = {
            snackbarHostState?.let { SnackbarHost(it, Modifier.floatAboveBottomChrome(LocalFlowBottomInsets.current)) }
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            if (header != null) {
                SettingsColumnFrame(Modifier.padding(horizontal = SettingsHorizontalPadding)) { header() }
            }
            val onTv = LocalDpadNavigation.current
            CompositionLocalProvider(LocalSettingsHighlight provides highlight) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(SettingsRowGap),
                    contentPadding =
                        PaddingValues(bottom = flowBottomContentPadding(SettingsBottomSpacing)),
                ) {
                    keys.reset()
                    SettingsListScope(this, keys, onTv = onTv).content()
                }
            }
        }
    }
}

/** Centres [content] and caps it at [SettingsMaxContentWidth]. */
@Composable
internal fun SettingsColumnFrame(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(
            modifier =
                Modifier
                    .widthIn(max = SettingsMaxContentWidth)
                    .fillMaxWidth()
                    .then(modifier),
        ) {
            content()
        }
    }
}

/**
 * The item keys a [SettingsPage] emitted, in order. The lazy list cannot map a key to an index for
 * items that were never laid out, so the page records them while it builds its content.
 */
internal class SettingsKeyRecorder {
    private val keys = mutableListOf<String>()

    fun reset() = keys.clear()

    /**
     * Records [key] and returns a lazy-list key that is unique on the page. A setting key may appear
     * twice, as a group header and as that group's first row; search scrolls to the first, the
     * highlight frames both, and the list must never see the same key twice.
     */
    fun add(key: String): String {
        val occurrence = keys.count { it == key }
        keys += key
        return if (occurrence == 0) key else "$key#$occurrence"
    }

    fun indexOf(key: String): Int = keys.indexOf(key)
}
