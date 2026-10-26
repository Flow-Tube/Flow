package io.github.aedev.flow.ui.components.layout

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The app shell's snackbar, drawn above the mini player and the navigation bar, where its action
 * can be tapped; a page's own Scaffold snackbar sits under them. Null outside the shell.
 */
val LocalFlowSnackbarHostState = staticCompositionLocalOf<SnackbarHostState?> { null }
