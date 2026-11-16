@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.aedev.flow.ui.components.musicplayer.mini

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.graphics.shapes.RoundedPolygon
import io.github.aedev.flow.data.local.CoverShape
import io.github.aedev.flow.ui.components.shared.fittedToCircle

/** The library shape behind each [CoverShape]. */
internal fun CoverShape.materialShape(): RoundedPolygon =
    when (this) {
        CoverShape.CIRCLE -> MaterialShapes.Circle
        CoverShape.SQUARE -> MaterialShapes.Square
        CoverShape.SLANTED -> MaterialShapes.Slanted
        CoverShape.ARCH -> MaterialShapes.Arch
        CoverShape.FAN -> MaterialShapes.Fan
        CoverShape.ARROW -> MaterialShapes.Arrow
        CoverShape.SEMI_CIRCLE -> MaterialShapes.SemiCircle
        CoverShape.OVAL -> MaterialShapes.Oval
        CoverShape.PILL -> MaterialShapes.Pill
        CoverShape.TRIANGLE -> MaterialShapes.Triangle
        CoverShape.DIAMOND -> MaterialShapes.Diamond
        CoverShape.CLAM_SHELL -> MaterialShapes.ClamShell
        CoverShape.PENTAGON -> MaterialShapes.Pentagon
        CoverShape.GEM -> MaterialShapes.Gem
        CoverShape.SUNNY -> MaterialShapes.Sunny
        CoverShape.VERY_SUNNY -> MaterialShapes.VerySunny
        CoverShape.COOKIE_4 -> MaterialShapes.Cookie4Sided
        CoverShape.COOKIE_6 -> MaterialShapes.Cookie6Sided
        CoverShape.COOKIE_7 -> MaterialShapes.Cookie7Sided
        CoverShape.COOKIE_9 -> MaterialShapes.Cookie9Sided
        CoverShape.COOKIE_12 -> MaterialShapes.Cookie12Sided
        CoverShape.GHOSTISH -> MaterialShapes.Ghostish
        CoverShape.CLOVER_4 -> MaterialShapes.Clover4Leaf
        CoverShape.CLOVER_8 -> MaterialShapes.Clover8Leaf
        CoverShape.BURST -> MaterialShapes.Burst
        CoverShape.SOFT_BURST -> MaterialShapes.SoftBurst
        CoverShape.BOOM -> MaterialShapes.Boom
        CoverShape.SOFT_BOOM -> MaterialShapes.SoftBoom
        CoverShape.FLOWER -> MaterialShapes.Flower
        CoverShape.PUFFY -> MaterialShapes.Puffy
        CoverShape.PUFFY_DIAMOND -> MaterialShapes.PuffyDiamond
        CoverShape.PIXEL_CIRCLE -> MaterialShapes.PixelCircle
        CoverShape.PIXEL_TRIANGLE -> MaterialShapes.PixelTriangle
        CoverShape.BUN -> MaterialShapes.Bun
        CoverShape.HEART -> MaterialShapes.Heart
    }

private val fitted = HashMap<CoverShape, RoundedPolygon>()

/** The shape fitted to a circle so it can turn and morph inside the cover's slot; made once per shape. */
internal fun CoverShape.coverPolygon(): RoundedPolygon = fitted.getOrPut(this) { materialShape().fittedToCircle() }
