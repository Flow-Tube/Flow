@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.aedev.flow.ui.components.musicplayer.full

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import io.github.aedev.flow.data.local.CoverShape
import io.github.aedev.flow.ui.components.musicplayer.lyrics.LyricsCoverLayer
import io.github.aedev.flow.ui.components.musicplayer.lyrics.rememberLyricsCover
import io.github.aedev.flow.ui.components.musicplayer.mini.coverPolygon
import io.github.aedev.flow.ui.components.musicplayer.mini.rememberTrackCoverShapes

private const val COVER_SCRIM_ALPHA = 0.4f
private const val SHAPE_ALPHA = 0.5f
private const val OWN_SHAPE_SCALE = 1.15f
private const val PARTNER_SHAPE_SCALE = 0.8f

/** How far down the partner starts, as a share of the height: clear of the title and controls below it. */
private const val PARTNER_SHAPE_TOP = 0.2f
private const val OWN_SHAPE_TURN = -18f
private const val PARTNER_SHAPE_TURN = 24f

/**
 * The cover's colours as the lyrics sheet's soft field, under a scrim so the controls keep their
 * contrast. [moving] lets the field drift; [running] stops it with the player, the song or the screen.
 */
@Composable
internal fun CoverColoursLayer(
    thumbnailUrl: String?,
    ground: Color,
    moving: Boolean,
    running: Boolean,
) {
    LyricsCoverLayer(
        cover = rememberLyricsCover(thumbnailUrl.orEmpty(), enabled = true),
        moving = moving,
        running = running,
    )
    Box(Modifier.fillMaxSize().background(ground.copy(alpha = COVER_SCRIM_ALPHA)))
}

/**
 * Two large Material shapes in the cover's tones, still: the song's own shape from the mini player
 * leaning in from the top start, and its partner from the end, both above the title and controls.
 */
@Composable
internal fun CoverShapesLayer(
    trackId: String,
    own: Color,
    partner: Color,
) {
    val shapes = rememberTrackCoverShapes(trackId)
    Crossfade(
        targetState = shapes,
        animationSpec = MaterialTheme.motionScheme.slowEffectsSpec(),
        label = "playerCoverShapes",
        modifier = Modifier.fillMaxSize().clipToBounds(),
    ) { (ownShape, partnerShape) ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val side = minOf(maxWidth, maxHeight)
            BigShape(
                shape = ownShape,
                color = own,
                turn = OWN_SHAPE_TURN,
                modifier =
                    Modifier
                        .align(
                            Alignment.TopStart,
                        ).offset(x = -side * 0.3f, y = -side * 0.2f)
                        .requiredSize(side * OWN_SHAPE_SCALE),
            )
            BigShape(
                shape = partnerShape,
                color = partner,
                turn = PARTNER_SHAPE_TURN,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = side * 0.35f, y = maxHeight * PARTNER_SHAPE_TOP)
                        .requiredSize(side * PARTNER_SHAPE_SCALE),
            )
        }
    }
}

@Composable
private fun BigShape(
    shape: CoverShape,
    color: Color,
    turn: Float,
    modifier: Modifier,
) {
    Box(
        modifier =
            modifier
                .graphicsLayer { rotationZ = turn }
                .clip(shape.coverPolygon().toShape())
                .background(color.copy(alpha = SHAPE_ALPHA)),
    )
}
