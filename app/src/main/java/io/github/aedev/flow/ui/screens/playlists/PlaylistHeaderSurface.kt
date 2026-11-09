package io.github.aedev.flow.ui.screens.playlists

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.ui.components.layout.LocalFlowBottomInsets
import io.github.aedev.flow.ui.components.shared.rememberMediaArtworkTint

// The desktop app's header column, which keeps the artwork a header rather than a poster.
internal val HeaderPaneWidth = 360.dp

/** The side pane's container, tinted from the playlist's artwork. No blur and no gradient. */
@Composable
internal fun PlaylistHeaderSurface(
    artworkUrl: String,
    content: @Composable () -> Unit,
) {
    val tint = rememberMediaArtworkTint(artworkUrl.takeIf(String::isNotBlank), followsSetting = false)
    Surface(
        color = tint.container,
        contentColor = tint.onContainer,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxSize().padding(start = 16.dp, bottom = 16.dp + LocalFlowBottomInsets.current.navigationBottom),
        content = content,
    )
}
