package io.github.aedev.flow.ui

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import io.github.aedev.flow.ui.components.shared.LocalPageAnimationScope

/**
 * A screen of the app graph. It paints the theme background behind its content, so while two
 * screens slide past each other neither shows through the other. Routes that leave on their first
 * frame use a bare `composable` instead.
 */
internal fun NavGraphBuilder.page(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) {
    composable(route = route, arguments = arguments) { entry ->
        val scope = this
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            CompositionLocalProvider(LocalPageAnimationScope provides scope) { scope.content(entry) }
        }
    }
}
