package io.github.aedev.flow.ui.components.shared.card

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.data.model.Video

enum class VideoCardLayout {
    /** The thumbnail across the full width with the details beneath. */
    Stacked,

    /** The thumbnail on the left with the details beside it. */
    Row,
}

enum class VideoCardDensity {
    Regular,

    /**
     * Three cards across a phone: a stacked card drops the avatar and the ⋮ button, whose menu the
     * long press still opens, and gives the title the full width. A row keeps its look and takes
     * only the narrower inset, so it lines up with the dense cards above it.
     */
    Dense,
}

object VideoCardDefaults {
    /** One grid column on a phone; callers widen it to line a row up with the grid above. */
    val RowThumbnailWidth: Dp = 168.dp

    /** The card's side inset, and the gap between its thumbnail, avatar and text. */
    val Inset: Dp = 12.dp

    /** A [VideoCardDensity.Dense] card's side inset: two make the 8 dp gap between thumbnails. */
    val DenseInset: Dp = 4.dp

    val AvatarSize: Dp = 40.dp

    val RowVerticalPadding: Dp = 8.dp

    /** The gap between a row's title and the lines under it. */
    val RowTitleGap: Dp = 6.dp

    /** The gap between the lines under a stacked card's thumbnail. */
    val StackedTextSpacing: Dp = 4.dp
}

/**
 * Where a feed grid puts one card: its shape, its inset and its thumbnail. A grid takes it from its
 * [io.github.aedev.flow.ui.components.shared.FeedGridPlan] so every feed places the same card the same way.
 */
@Immutable
data class FeedCardSpec(
    val layout: VideoCardLayout,
    val useInternalPadding: Boolean,
    val thumbnailWidth: Dp,
    val density: VideoCardDensity,
    val fullBleed: Boolean,
)

@Composable
fun MediaVideoCard(
    video: Video,
    spec: FeedCardSpec,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showChannel: Boolean = true,
    reason: String? = null,
    whyShown: String? = null,
) {
    MediaVideoCard(
        video = video,
        onClick = onClick,
        modifier = modifier,
        layout = spec.layout,
        showChannel = showChannel,
        useInternalPadding = spec.useInternalPadding,
        thumbnailWidth = spec.thumbnailWidth,
        reason = reason,
        whyShown = whyShown,
        density = spec.density,
        fullBleed = spec.fullBleed,
    )
}

/**
 * One video as a card. Every screen that lists videos draws them through here, so a video looks and
 * behaves the same on Home, Subscriptions, Search, Channel, Categories and the related list.
 *
 * [showChannel] hides the avatar and channel name where the screen already names the channel.
 * [useInternalPadding] insets a stacked card; container-padded grids pass false. [thumbnailWidth]
 * sizes a row's thumbnail, so a row the grid could not fill still lines up with the cards above it.
 * [density] is the grid's [io.github.aedev.flow.ui.components.FeedGridLayout.cardDensity].
 * [reason] is a short line under the details saying why the feed shows this video; null shows none.
 * [whyShown] says why a feed shows this video, on the menu's Details page.
 * [fullBleed] draws a stacked card's thumbnail without corners, for the full-width card style.
 */
@Composable
fun MediaVideoCard(
    video: Video,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    layout: VideoCardLayout = VideoCardLayout.Stacked,
    showChannel: Boolean = true,
    useInternalPadding: Boolean = true,
    thumbnailWidth: Dp = VideoCardDefaults.RowThumbnailWidth,
    reason: String? = null,
    whyShown: String? = null,
    density: VideoCardDensity = VideoCardDensity.Regular,
    fullBleed: Boolean = false,
) {
    when (layout) {
        VideoCardLayout.Stacked -> {
            VideoCardStacked(
                video = video,
                onClick = onClick,
                showChannel = showChannel,
                useInternalPadding = useInternalPadding,
                density = density,
                reason = reason,
                whyShown = whyShown,
                fullBleed = fullBleed,
                modifier = modifier,
            )
        }

        VideoCardLayout.Row -> {
            VideoCardRow(
                video = video,
                onClick = onClick,
                showChannel = showChannel,
                thumbnailWidth = thumbnailWidth,
                inset = density.inset,
                reason = reason,
                whyShown = whyShown,
                modifier = modifier,
            )
        }
    }
}

internal val VideoCardDensity.inset: Dp
    get() =
        when (this) {
            VideoCardDensity.Regular -> VideoCardDefaults.Inset
            VideoCardDensity.Dense -> VideoCardDefaults.DenseInset
        }
