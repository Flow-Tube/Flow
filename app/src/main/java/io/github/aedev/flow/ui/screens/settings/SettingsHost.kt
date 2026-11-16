package io.github.aedev.flow.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import io.github.aedev.flow.ui.components.layout.rememberFlowPaneScaffoldDirective
import io.github.aedev.flow.ui.components.settings.LocalNewSettings
import io.github.aedev.flow.ui.components.settings.SettingsDestination
import io.github.aedev.flow.ui.components.settings.SettingsTarget
import io.github.aedev.flow.ui.components.shared.LocalDpadNavigation
import io.github.aedev.flow.ui.components.shared.dpadFocusGroup
import io.github.aedev.flow.ui.components.shared.dpadFocusOnEnter
import io.github.aedev.flow.ui.screens.settings.home.SettingsHomeScreen
import kotlinx.coroutines.launch

/**
 * The single Settings route: the settings list and the open page as a list-detail pair. On a phone
 * they are one pane at a time; from an expanded window they sit side by side.
 *
 * Sub-pages (Theme under Appearance, Buffer under Player) stack inside the detail pane rather than
 * through the pane navigator, so a sub-page never replaces the settings list beside it.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun SettingsHost(
    start: SettingsTarget?,
    onExit: () -> Unit,
    onOpenDonations: () -> Unit,
    onOpenRecap: () -> Unit,
    onOpenUpdate: () -> Unit,
    options: SettingsHostOptions = SettingsHostOptions(),
) {
    val navigator = rememberListDetailPaneScaffoldNavigator<String>(scaffoldDirective = rememberFlowPaneScaffoldDirective())
    val scope = rememberCoroutineScope()
    var stack by rememberSaveable { mutableStateOf(emptyList<String>()) }
    // A page leaves composition while a sub-page covers it; this keeps its scroll for the way back.
    val pageStates = rememberSaveableStateHolder()
    var startConsumed by rememberSaveable { mutableStateOf(false) }
    val dpad = LocalDpadNavigation.current
    val listFocus = remember { FocusRequester() }
    val selectedRowFocus = remember { FocusRequester() }
    val pageFocus = remember { FocusRequester() }
    var pageHasFocus by remember { mutableStateOf(false) }

    val twoPane =
        navigator.scaffoldValue[ListDetailPaneScaffoldRole.List] == PaneAdaptedValue.Expanded &&
            navigator.scaffoldValue[ListDetailPaneScaffoldRole.Detail] == PaneAdaptedValue.Expanded
    val current = stack.lastOrNull()?.let(SettingsTarget::decode) ?: SettingsTarget(SettingsDestination.APPEARANCE)

    fun updateStack(next: List<String>) {
        val kept = next.mapNotNull { SettingsTarget.decode(it)?.destination }
        stack
            .mapNotNull { SettingsTarget.decode(it)?.destination }
            .filterNot { it in kept }
            .forEach { pageStates.removeState(it.id) }
        stack = next
    }

    fun open(target: SettingsTarget) {
        // A new page takes focus as it enters; the one already open beside the list has to be asked.
        if (dpad && twoPane && target.destination == current.destination) pageFocus.requestFocus()
        // Opening from the list is a fresh visit, so even the page already showing starts from the top.
        pageStates.removeState(target.destination.id)
        updateStack(
            target.destination.path
                .dropLast(1)
                .map { SettingsTarget(it).encode() } + target.encode(),
        )
        scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, target.destination.root.id) }
    }

    fun push(target: SettingsTarget) {
        val settled =
            stack.dropLast(1) +
                listOfNotNull(
                    stack
                        .lastOrNull()
                        ?.let(SettingsTarget::decode)
                        ?.copy(highlight = null)
                        ?.encode(),
                )
        stack = settled + target.encode()
    }

    fun back() {
        if (stack.size > 1) {
            updateStack(stack.dropLast(1))
        } else {
            pageStates.removeState(current.destination.id)
            scope.launch { if (!navigator.navigateBack()) onExit() }
        }
    }

    LaunchedEffect(start) {
        if (start != null && !startConsumed) {
            startConsumed = true
            open(start)
        }
    }

    // Beside the list, OK on a page moves a remote into it; Back from that page's top level returns to
    // the page's row, or to the list when that row has scrolled away. Registered before the pages, so a page's own Back comes first.
    BackHandler(enabled = dpad && twoPane && stack.size <= 1 && pageHasFocus) {
        if (!selectedRowFocus.requestFocus()) listFocus.requestFocus()
    }

    CompositionLocalProvider(LocalNewSettings provides rememberNewSettings()) {
        NavigableListDetailPaneScaffold(
            navigator = navigator,
            listPane = {
                AnimatedPane(modifier = Modifier.focusRequester(listFocus).dpadFocusGroup()) {
                    SettingsHomeScreen(
                        selected = if (twoPane) current.destination.root else null,
                        onOpen = ::open,
                        onOpenDonations = onOpenDonations,
                        onOpenUpdate = onOpenUpdate,
                        onBack = onExit,
                        selectedRowFocus = selectedRowFocus,
                    )
                }
            },
            detailPane = {
                AnimatedPane(
                    modifier =
                        if (dpad) {
                            Modifier
                                .onFocusChanged { pageHasFocus = it.hasFocus }
                                .focusRequester(pageFocus)
                                .dpadFocusGroup()
                        } else {
                            Modifier
                        },
                ) {
                    val spec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
                    AnimatedContent(
                        targetState = current,
                        transitionSpec = { fadeIn(spec) togetherWith fadeOut(spec) },
                        contentKey = { it.destination },
                        label = "settingsDetail",
                    ) { target ->
                        Box(modifier = dpadFocusOnEnter()) {
                            pageStates.SaveableStateProvider(target.destination.id) {
                                SettingsDetail(
                                    target = target,
                                    onBack = if (twoPane && stack.size <= 1) null else ::back,
                                    onNavigate = ::push,
                                    onOpenRecap = onOpenRecap,
                                    options = options,
                                )
                            }
                        }
                    }
                }
            },
        )
    }

    BackHandler(enabled = stack.size > 1) { updateStack(stack.dropLast(1)) }
}

/**
 * Pages a host draws in place of the shared ones, or adds: TV has no camera, so its Sync page only
 * hosts, and it supplies the remote's key guide.
 */
@Immutable
class SettingsHostOptions(
    val pages: Map<SettingsDestination, @Composable (onBack: (() -> Unit)?) -> Unit> = emptyMap(),
)
