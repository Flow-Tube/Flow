package io.github.aedev.flow.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter

/**
 * Simple in-process event bus that notifies screens when the user taps on
 * the bottom-nav item that is already selected (i.e., they want to scroll
 * back to the top / refresh the current page).
 *
 * Usage:
 *   - Emit:   TabScrollEventBus.emitScrollToTop("home")
 *   - Listen: TabScrollEventBus.scrollToTopEvents.filter { it == "home" }.collect { … }
 */
object TabScrollEventBus {
    private val _scrollToTopEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val scrollToTopEvents: SharedFlow<String> = _scrollToTopEvents.asSharedFlow()

    /**
     * Signal the screen identified by [route] to scroll back to the top.
     */
    fun emitScrollToTop(route: String) {
        _scrollToTopEvents.tryEmit(route)
    }

    private val _scrolledToTopEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** Sent by a screen once it has finished a scroll asked for by [emitScrollToTop]. */
    val scrolledToTopEvents: SharedFlow<String> = _scrolledToTopEvents.asSharedFlow()

    fun emitScrolledToTop(route: String) {
        _scrolledToTopEvents.tryEmit(route)
    }
}

/** Runs [onReselect] each time the tab rooted at [route] is tapped while its root is showing. */
@Composable
fun OnTabReselected(
    route: String,
    onReselect: suspend () -> Unit,
) {
    val action by rememberUpdatedState(onReselect)
    LaunchedEffect(route) {
        TabScrollEventBus.scrollToTopEvents.filter { it == route }.collectLatest { action() }
    }
}
