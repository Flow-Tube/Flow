package io.github.aedev.flow.ui.components.musicplayer.mini

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.data.local.CoverShape
import io.github.aedev.flow.data.local.DefaultCoverShapes
import io.github.aedev.flow.data.local.MiniBarBackground
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.ui.components.musicplayer.lyrics.LyricsCoverLayer
import io.github.aedev.flow.ui.components.musicplayer.lyrics.rememberLyricsCover
import io.github.aedev.flow.ui.components.shared.MediaMiniBarDefaults
import io.github.aedev.flow.ui.theme.MusicPlayerTrueBlack
import io.github.aedev.flow.ui.theme.PlayerScrimContent
import io.github.aedev.flow.ui.theme.PlayerScrimContentSecondary
import io.github.aedev.flow.ui.theme.PlayerScrimImmersiveBackdrop

private val OutlineWidth = 1.dp

/**
 * Paints the mini bar in [style] over the player's card and hands its content the scheme that reads
 * on it. The ambient scheme is the player's; [controlScheme] is what the bar's content uses today and
 * [appScheme] the app's own. Every look is still: the cover colours are the lyrics' field at rest.
 */
@Composable
internal fun MiniBarSurface(
    style: MiniBarBackground,
    artworkUrl: String,
    controlScheme: ColorScheme,
    appScheme: ColorScheme,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val playerScheme = MaterialTheme.colorScheme
    val scheme =
        remember(style, controlScheme, appScheme, playerScheme) {
            when (style) {
                MiniBarBackground.TINTED -> {
                    controlScheme
                }

                MiniBarBackground.VIVID -> {
                    controlScheme.copy(
                        onSurface = playerScheme.onPrimaryContainer,
                        onSurfaceVariant = playerScheme.onPrimaryContainer,
                    )
                }

                MiniBarBackground.APP_THEME, MiniBarBackground.OUTLINED -> {
                    appScheme
                }

                MiniBarBackground.COVER_COLOURS, MiniBarBackground.BLACK -> {
                    controlScheme.copy(onSurface = PlayerScrimContent, onSurfaceVariant = PlayerScrimContentSecondary)
                }
            }
        }
    val paint =
        when (style) {
            MiniBarBackground.TINTED, MiniBarBackground.COVER_COLOURS -> {
                Modifier
            }

            MiniBarBackground.VIVID -> {
                Modifier.background(playerScheme.primaryContainer)
            }

            MiniBarBackground.APP_THEME -> {
                Modifier.background(appScheme.surfaceContainerHigh)
            }

            MiniBarBackground.BLACK -> {
                Modifier.background(MusicPlayerTrueBlack)
            }

            MiniBarBackground.OUTLINED -> {
                Modifier
                    .background(appScheme.surface)
                    .border(OutlineWidth, appScheme.outlineVariant, RoundedCornerShape(MediaMiniBarDefaults.CornerRadius))
            }
        }
    Box(modifier = modifier.fillMaxSize().then(paint)) {
        if (style == MiniBarBackground.COVER_COLOURS) {
            LyricsCoverLayer(cover = rememberLyricsCover(artworkUrl, enabled = true), moving = false, running = false)
            Box(Modifier.fillMaxSize().background(PlayerScrimImmersiveBackdrop))
        }
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** The song's own cover shape, as the mini player draws it, and the next of the user's shapes to go with it. */
@Composable
internal fun rememberTrackCoverShapes(trackId: String): Pair<CoverShape, CoverShape> {
    val context = LocalContext.current
    val preferences = remember(context) { PlayerPreferences(context) }
    val shapes by preferences.miniCoverShapes.collectAsState(initial = DefaultCoverShapes)
    return remember(trackId, shapes) { trackCoverShapes(trackId, shapes) }
}

internal fun trackCoverShapes(
    trackId: String,
    shapes: List<CoverShape>,
): Pair<CoverShape, CoverShape> {
    val index = miniCoverShapeIndex(trackId, shapes.size)
    val own = shapes[index]
    val partner =
        if (shapes.size > 1) {
            shapes[(index + 1) % shapes.size]
        } else if (own == CoverShape.CIRCLE) {
            CoverShape.COOKIE_9
        } else {
            CoverShape.CIRCLE
        }
    return own to partner
}
