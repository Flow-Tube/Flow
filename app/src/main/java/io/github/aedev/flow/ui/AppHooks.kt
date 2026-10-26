package io.github.aedev.flow.ui

import android.content.Context
import android.net.Uri
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import io.github.aedev.flow.R
import io.github.aedev.flow.data.shorts.queue.ShortsQueueSource
import io.github.aedev.flow.ui.components.layout.navigation.DOWNLOADS_ROUTE
import io.github.aedev.flow.ui.components.layout.navigation.DOWNLOADS_TAB_ROUTE
import io.github.aedev.flow.utils.NetworkConnectivityObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * Suspends until the NavHost has set its graph. The NavHost is composed only once the onboarding
 * check resolves, so a fresh activity has a window where navigate() throws (#1079, #1102).
 */
suspend fun NavController.awaitGraph() {
    currentBackStackEntryFlow.first()
}

/** Navigates to a route handed in from outside the graph, once the graph has its first entry. */
@Composable
fun HandlePendingRoute(
    pendingRoute: String?,
    navController: NavController,
    startRoute: String,
    downloadsTab: suspend () -> Boolean,
    onConsumed: () -> Unit,
    onBeforeNavigate: () -> Unit = {},
) {
    LaunchedEffect(pendingRoute) {
        pendingRoute?.let { route ->
            navController.awaitGraph()
            onBeforeNavigate()
            val tab = flowTabForRoute(route, downloadsTab())
            when {
                tab != null -> navController.navigateToTab(tab, startRoute)
                else -> navController.openPage(route)
            }
            onConsumed()
        }
    }
}

/** [asksFirst] turns a video into a question instead: [onAsk] gets it and nothing plays yet. */
@Composable
fun HandleDeepLinks(
    deeplinkVideoId: String?,
    isShort: Boolean,
    navController: NavController,
    onDeeplinkConsumed: () -> Unit,
    asksFirst: suspend () -> Boolean = { false },
    onAsk: (videoId: String) -> Unit = {},
) {
    val currentAsksFirst by rememberUpdatedState(asksFirst)
    LaunchedEffect(deeplinkVideoId, isShort) {
        val videoId = deeplinkVideoId ?: return@LaunchedEffect
        if (!isShort && currentAsksFirst()) {
            onAsk(videoId)
            onDeeplinkConsumed()
            return@LaunchedEffect
        }
        navController.awaitGraph()
        if (isShort) {
            // Every Shorts queue shares one route, so single-top would reuse whichever Shorts screen
            // is on top and keep its old queue instead of opening the linked short.
            navController.openShorts(ShortsQueueSource.SeededFeed(videoId))
        } else {
            navController.navigate("player/$videoId") {
                launchSingleTop = true
            }
        }
        onDeeplinkConsumed()
    }
}

private const val OFFLINE_NOTICE_DELAY_MS = 3_000L

@Composable
fun OfflineMonitor(
    context: Context,
    snackbarHostState: SnackbarHostState,
    currentRoute: State<String>,
    onOpenDownloads: () -> Unit,
) {
    val openDownloads by rememberUpdatedState(onOpenDownloads)
    val connectivity = remember(context) { NetworkConnectivityObserver(context) }
    val isConnected by remember(connectivity) { connectivity.observeConnectivity() }
        .collectAsStateWithLifecycle(initialValue = true)
    val route = currentRoute.value

    LaunchedEffect(isConnected, route) {
        if (isConnected) return@LaunchedEffect

        val isSafeRoute =
            route == DOWNLOADS_ROUTE ||
                route == DOWNLOADS_TAB_ROUTE ||
                route.startsWith("player") ||
                route.startsWith("musicPlayer") ||
                route == "settings" ||
                route == "notes"
        if (isSafeRoute) return@LaunchedEffect

        delay(OFFLINE_NOTICE_DELAY_MS)

        val result =
            snackbarHostState.showSnackbar(
                message = context.getString(R.string.error_no_internet_found),
                actionLabel = context.getString(R.string.downloads_title),
                duration = SnackbarDuration.Short,
            )
        if (result == SnackbarResult.ActionPerformed) {
            openDownloads()
        }
    }
}
