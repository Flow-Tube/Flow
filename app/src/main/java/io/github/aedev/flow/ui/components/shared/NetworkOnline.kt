package io.github.aedev.flow.ui.components.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * Whether the device can reach the internet, from the app's one connectivity callback. Defaults
 * to online, so a surface outside the app scaffold (a preview, a test) never sees a reconnect.
 */
val LocalNetworkOnline = staticCompositionLocalOf<State<Boolean>> { mutableStateOf(true) }

/** Runs [onBackOnline] once each time the connection comes back, never on first composition. */
@Composable
fun BackOnlineEffect(onBackOnline: () -> Unit) {
    val online = LocalNetworkOnline.current
    val action by rememberUpdatedState(onBackOnline)
    LaunchedEffect(online) {
        snapshotFlow { online.value }.backOnlineEdges().collect { action() }
    }
}

/**
 * A paged feed whose last load failed loads again when the connection returns. One retry per
 * reconnect, so a feed that fails again waits for the next one or for the Retry button.
 */
@Composable
fun RetryWhenBackOnline(items: LazyPagingItems<*>) {
    val current by rememberUpdatedState(items)
    BackOnlineEffect {
        val states = current.loadState
        if (states.refresh is LoadState.Error || states.append is LoadState.Error) current.retry()
    }
}

/** One emission per offline to online change; the current value on collection is not a change. */
internal fun Flow<Boolean>.backOnlineEdges(): Flow<Unit> =
    distinctUntilChanged()
        .drop(1)
        .filter { it }
        .map { }
