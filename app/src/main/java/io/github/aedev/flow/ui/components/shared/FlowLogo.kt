package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import io.github.aedev.flow.R

private const val LOGO_ASPECT = 48f / 36f

/** The app's badge, drawn in theme colours so it follows every palette. Size it by width. */
@Composable
fun FlowLogo(
    modifier: Modifier = Modifier,
    shapeColor: Color = MaterialTheme.colorScheme.primary,
    glyphColor: Color = MaterialTheme.colorScheme.onPrimary,
) {
    Box(modifier.aspectRatio(LOGO_ASPECT)) {
        Icon(
            painter = painterResource(R.drawable.ic_flow_badge_shape),
            contentDescription = null,
            tint = shapeColor,
            modifier = Modifier.fillMaxSize(),
        )
        Icon(
            painter = painterResource(R.drawable.ic_flow_badge_glyph),
            contentDescription = null,
            tint = glyphColor,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
