package io.github.aedev.flow.ui.components.videoplayer.controls

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.theme.PlayerScrimContent
import io.github.aedev.flow.ui.theme.PlayerScrimContentSecondary
import io.github.aedev.flow.ui.utils.fadingEdge

private val TitleFadeWidth = 36.dp

/**
 * Title and channel under the portrait-fullscreen action row.
 *
 * Portrait fullscreen has a full screen width to spend and nothing competing for it, so the title
 * gets its own line at headline weight instead of the sliver left between the top bar's buttons,
 * where it truncated to two or three words. An overrunning title fades out at the end rather than
 * ellipsing — no marquee, because this layer stays composed while hidden.
 */
@Composable
internal fun PortraitFullscreenTitle(
    videoTitle: String?,
    channelName: String?,
    horizontalPadding: () -> Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (videoTitle.isNullOrBlank()) return

    val openDescription = stringResource(R.string.description)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable(onClick = onClick, onClickLabel = openDescription)
                .insetPadding(horizontalPadding),
    ) {
        Text(
            text = videoTitle,
            color = PlayerScrimContent,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .fadingEdge(end = TitleFadeWidth),
        )

        if (!channelName.isNullOrBlank()) {
            Text(
                text = channelName,
                color = PlayerScrimContentSecondary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
