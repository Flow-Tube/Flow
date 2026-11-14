package io.github.aedev.flow.ui.screens.playon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import io.github.aedev.flow.sync2.live.LiveMessage
import io.github.aedev.flow.ui.components.shared.VideoThumbnailImage

/** Album art is square until it loads and shows its own shape. */
private const val ALBUM_RATIO = 1f
private const val VIDEO_RATIO = 16f / 9f

/** Still asking the music service for a song's art. */
private const val LOOKING = ""

/**
 * A session's artwork at its own aspect ratio: a song's album art, a video's frame. [byHeight] sizes
 * it from the height it is given instead of the width. Without [lookUpArt], a song shows the album
 * art only when it is already known, and its video's frame otherwise, so nothing is asked of the
 * music service.
 */
@Composable
internal fun SessionArtwork(
    now: LiveMessage.Now,
    shape: Shape,
    modifier: Modifier = Modifier,
    byHeight: Boolean = false,
    lookUpArt: Boolean = true,
) {
    val video = now.video ?: return
    val playOn = LocalPlayOn.current
    val art by produceState<String?>(if (now.music && lookUpArt) LOOKING else null, video, now.music, lookUpArt) {
        value =
            when {
                !now.music -> null
                lookUpArt -> playOn?.musicArt(video)
                else -> playOn?.knownMusicArt(video)
            }
    }
    var ratio by remember(art) { mutableFloatStateOf(if (art == null) VIDEO_RATIO else ALBUM_RATIO) }
    val sized = modifier.aspectRatio(ratio, matchHeightConstraintsFirst = byHeight).clip(shape)
    when (val url = art) {
        null -> {
            // The crop drops the bars the fallback thumbnail pads a 16:9 frame with.
            VideoThumbnailImage(
                videoId = video,
                model = null,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = sized,
            )
        }

        LOOKING -> {
            Box(sized.background(MaterialTheme.colorScheme.surfaceContainerHigh))
        }

        else -> {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = sized,
                onSuccess = { state ->
                    state.painter.intrinsicSize
                        .takeIf { it.isSpecified && it.height > 0f }
                        ?.let { ratio = it.width / it.height }
                },
            )
        }
    }
}
