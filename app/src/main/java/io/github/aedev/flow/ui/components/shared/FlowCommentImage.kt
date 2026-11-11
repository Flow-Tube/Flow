package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.model.CommentImage
import io.github.aedev.flow.utils.NetworkState
import kotlinx.coroutines.flow.combine

private val MaxImageHeight = 200.dp

/**
 * The GIF (or picture) under a comment's text, at the shape YouTube gives for it. It plays a couple
 * of times and stops; a tap opens it full size. With Data Saver on, or GIFs kept to Wi-Fi while off
 * it, nothing downloads until the person asks.
 */
@Composable
fun FlowCommentImage(
    image: CommentImage,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val preferences = remember(context) { PlayerPreferences(context) }
    val settings by remember(preferences) {
        combine(preferences.commentGifsEnabled, preferences.commentGifsWifiOnly, ::Pair)
    }.collectAsStateWithLifecycle(initialValue = null)
    val (enabled, wifiOnly) = settings ?: return
    if (!enabled) return

    val loadsNow = remember(wifiOnly) { !NetworkState.isDataSaverOn(context) && (!wifiOnly || NetworkState.isOnWifi(context)) }
    var requested by rememberSaveable(image.url) { mutableStateOf(false) }
    var showFullSize by remember { mutableStateOf(false) }

    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .heightIn(max = MaxImageHeight)
                .aspectRatio(image.width.toFloat() / image.height, matchHeightConstraintsFirst = true)
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        if (loadsNow || requested) {
            AsyncImage(
                model = rememberLoopCappedImageRequest(image.url),
                contentDescription = stringResource(R.string.comment_gif_description),
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .clickable { showFullSize = true },
            )
        } else {
            FilledTonalButton(onClick = { requested = true }) {
                Text(stringResource(R.string.comment_gif_show))
            }
        }
    }

    if (showFullSize) {
        FullSizeImageDialog(imageUrl = image.url, onDismiss = { showFullSize = false })
    }
}
