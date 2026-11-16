package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.ui.components.FeedGridLayout
import io.github.aedev.flow.ui.components.rememberDeArrowResult
import io.github.aedev.flow.ui.components.shared.card.LocalVideoCardPreferences
import io.github.aedev.flow.ui.components.shared.card.rememberOriginalTitle
import io.github.aedev.flow.ui.components.shared.quickactions.VideoQuickActionsBottomSheet
import io.github.aedev.flow.ui.components.shared.quickactions.sharedQuickActionsViewModel
import io.github.aedev.flow.ui.theme.extendedColors
import io.github.aedev.flow.utils.formatViewCount

/** The one size every Shorts grid and strip agrees on, so cells line up across screens. */
object ShortCardDefaults {
    val MinWidth = 160.dp
    val Spacing = 12.dp

    /** The gap between reels in a horizontal strip, a little tighter than a grid's. */
    val StripSpacing = 10.dp
    const val ASPECT_RATIO = 9f / 16f

    /** A Shorts grid's side padding: its own on a phone, the feed's margin once the window widens. */
    fun gridPadding(
        layout: FeedGridLayout,
        compactPadding: Dp = Spacing,
    ): Dp = if (layout.isCompact) compactPadding else layout.contentPadding + layout.cardInset
}

private val TitleSpacing = 8.dp

/**
 * A portrait reel card: poster, title, view count. Long-pressing opens the video quick actions.
 * The channel Shorts grid, search, history, the library shelves and the home strip all draw this
 * one card; a grid passes `Modifier.fillMaxWidth()` and a strip keeps the default width.
 */
@Composable
fun MediaShortCard(
    video: Video,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.width(ShortCardDefaults.MinWidth),
    trailingContent: (@Composable () -> Unit)? = null,
    removableFromSavedShorts: Boolean = false,
) {
    var showQuickActions by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val preferences = LocalVideoCardPreferences.current
    // Title only: DeArrow frames are landscape, and a reel poster is portrait.
    val deArrowTitle = rememberDeArrowResult(video, preferences.deArrowEnabled, useRandomFrame = false)?.title
    val originalTitle = rememberOriginalTitle(video.id, preferences.originalTitlesEnabled && deArrowTitle == null)
    val title = deArrowTitle ?: originalTitle ?: video.title
    Column(
        modifier =
            modifier
                .pressScale(interactionSource)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = ripple(),
                    onLongClick = { showQuickActions = true },
                    onClick = onClick,
                ),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(ShortCardDefaults.ASPECT_RATIO)
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .thumbnailGradientOverlay(),
        ) {
            VideoThumbnailImage(
                videoId = video.id,
                model = video.thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                portrait = true,
            )
            ShortWatchedIndicator(videoId = video.id)
        }
        Spacer(modifier = Modifier.height(TitleSpacing))
        Text(
            text = title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.views_template, formatViewCount(video.viewCount)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.extendedColors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            trailingContent?.invoke()
        }
    }

    if (showQuickActions) {
        val quickActions = sharedQuickActionsViewModel()
        VideoQuickActionsBottomSheet(
            video = video,
            title = title,
            onDismiss = { showQuickActions = false },
            onRemoveFromCollection = { quickActions.removeFromSavedShorts(video) }.takeIf { removableFromSavedShorts },
            removeFromCollectionLabel = stringResource(R.string.remove_from_saved_shorts).takeIf { removableFromSavedShorts },
            viewModel = quickActions,
        )
    }
}
