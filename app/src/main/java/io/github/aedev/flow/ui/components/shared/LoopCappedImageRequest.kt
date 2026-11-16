package io.github.aedev.flow.ui.components.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import coil3.compose.LocalPlatformContext
import coil3.gif.repeatCount
import coil3.request.ImageRequest

private const val ANIMATED_IMAGE_REPEATS = 2

/**
 * An image request whose GIF or animated WebP plays a few times and stops on its last frame. Posts
 * and comments stay composed out of sight (under the expanded player, in a kept list), so an
 * endless loop would keep drawing frames nobody sees.
 */
@Composable
fun rememberLoopCappedImageRequest(url: String): ImageRequest {
    val context = LocalPlatformContext.current
    return remember(context, url) {
        ImageRequest
            .Builder(context)
            .data(url)
            .repeatCount(ANIMATED_IMAGE_REPEATS)
            .build()
    }
}
