package io.github.aedev.flow.ui.screens.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.PathParser

// SVG path data — a single unbreakable token, so the line-length rule cannot be satisfied by wrapping.
@Suppress("ktlint:standard:max-line-length")
private const val FLOW_LOGO_BG_PATH = "M21.58 7.16C21.33 6.22 20.59 5.48 19.65 5.23C17.96 4.77 12 4.77 12 4.77C12 4.77 6.04 4.77 4.35 5.23C3.41 5.48 2.67 6.22 2.42 7.16C1.96 8.85 1.96 12.38 1.96 12.38C1.96 12.38 1.96 15.91 2.42 17.6C2.67 18.54 3.41 19.28 4.35 19.53C6.04 19.99 12 19.99 12 19.99C12 19.99 17.96 19.99 19.65 19.53C20.59 19.28 21.33 18.54 21.58 17.6C22.04 15.91 22.04 12.38 22.04 12.38C22.04 12.38 22.04 8.85 21.58 7.16Z"
private const val FLOW_LOGO_GLYPH_PATH = "M10 7L18 7L17.2 9.5H12.8L12.2 11.5H16L15.2 14H11.5L10.5 17H7.5L10 7Z"

@Suppress("ktlint:standard:max-line-length")
private const val FLOW_INCOGNITO_GLYPH_PATH = "M17.06 13C15.2 13 13.64 14.33 13.24 16.1C12.29 15.69 11.42 15.8 10.76 16.09C10.35 14.31 8.79 13 6.94 13C4.77 13 3 14.79 3 17C3 19.21 4.77 21 6.94 21C9 21 10.68 19.38 10.84 17.32C11.18 17.08 12.07 16.63 13.16 17.34C13.34 19.39 15 21 17.06 21C19.23 21 21 19.21 21 17C21 14.79 19.23 13 17.06 13M6.94 19.86C5.38 19.86 4.13 18.58 4.13 17S5.39 14.14 6.94 14.14C8.5 14.14 9.75 15.42 9.75 17S8.5 19.86 6.94 19.86M17.06 19.86C15.5 19.86 14.25 18.58 14.25 17S15.5 14.14 17.06 14.14C18.62 14.14 19.88 15.42 19.88 17S18.61 19.86 17.06 19.86M22 10.5H2V12H22V10.5M15.53 2.63C15.31 2.14 14.75 1.88 14.22 2.05L12 2.79L9.77 2.05L9.72 2.04C9.19 1.89 8.63 2.17 8.43 2.68L6 9H18L15.56 2.68L15.53 2.63Z"

/** The Home logo and the wordmark beside it: a tap does what tapping the Home tab again does, a long-press switches Deep Flow. */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.homeLogoClicks(
    clickLabel: String,
    longClickLabel: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
): Modifier = combinedClickable(onClickLabel = clickLabel, onLongClickLabel = longClickLabel, onClick = onClick, onLongClick = onLongClick)

@Composable
internal fun FlowHeaderLogoIcon(
    isDeepFlowActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val onPrimaryColor = MaterialTheme.colorScheme.onPrimary

    val bgPath =
        remember {
            PathParser().parsePathString(FLOW_LOGO_BG_PATH).toPath()
        }
    val glyphPath =
        remember {
            PathParser().parsePathString(FLOW_LOGO_GLYPH_PATH).toPath()
        }
    val incognitoPath =
        remember {
            PathParser().parsePathString(FLOW_INCOGNITO_GLYPH_PATH).toPath()
        }

    val glyphAlpha by animateFloatAsState(
        targetValue = if (isDeepFlowActive) 0f else 1f,
        animationSpec = tween(durationMillis = 250),
        label = "deepFlowGlyphAlpha",
    )
    val incognitoAlpha by animateFloatAsState(
        targetValue = if (isDeepFlowActive) 1f else 0f,
        animationSpec = tween(durationMillis = 250),
        label = "deepFlowIncognitoAlpha",
    )

    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        drawContext.canvas.save()
        drawContext.canvas.scale(sx, sy)
        drawPath(path = bgPath, color = primaryColor)

        if (glyphAlpha > 0f) {
            drawPath(path = glyphPath, color = onPrimaryColor.copy(alpha = glyphAlpha))
        }
        if (incognitoAlpha > 0f) {
            val incognitoScale = 0.65f
            val incognitoOffset = 12f * (1f - incognitoScale)
            drawContext.canvas.save()
            drawContext.canvas.translate(incognitoOffset, incognitoOffset)
            drawContext.canvas.scale(incognitoScale, incognitoScale)
            drawPath(path = incognitoPath, color = onPrimaryColor.copy(alpha = incognitoAlpha))
            drawContext.canvas.restore()
        }

        drawContext.canvas.restore()
    }
}
