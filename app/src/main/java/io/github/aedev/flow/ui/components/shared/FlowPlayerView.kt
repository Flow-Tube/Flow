package io.github.aedev.flow.ui.components.shared

import android.graphics.Outline
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider
import androidx.annotation.LayoutRes
import androidx.media3.ui.PlayerView
import io.github.aedev.flow.R
import io.github.aedev.flow.player.surface.VideoSurfacePolicy

/**
 * The controller-less PlayerView layout on the surface type this device plays smoothest with, or
 * the spherical one a 360 video is looked around in. PlayerView fixes its surface when inflated.
 */
@LayoutRes
fun flowPlayerViewLayout(spherical: Boolean = false): Int =
    when {
        spherical -> R.layout.video_player_view_spherical
        VideoSurfacePolicy.usesSurfaceView(Build.VERSION.SDK_INT) -> R.layout.video_player_view_surface
        else -> R.layout.video_player_view
    }

/**
 * Rounds the view's corners, video surface included, which a Compose clip cannot reach. A radius
 * of zero leaves it square.
 */
fun PlayerView.clipToCornerRadius(radiusPx: Float) {
    val current = getTag(R.id.player_view) as? Float
    if (current != null && current == radiusPx) return
    if (radiusPx <= 0f) {
        clipToOutline = false
        outlineProvider = ViewOutlineProvider.BACKGROUND
    } else {
        outlineProvider =
            object : ViewOutlineProvider() {
                override fun getOutline(
                    v: View,
                    outline: Outline,
                ) {
                    outline.setRoundRect(0, 0, v.width, v.height, radiusPx)
                }
            }
        clipToOutline = true
    }
    invalidateOutline()
    setTag(R.id.player_view, radiusPx)
}
