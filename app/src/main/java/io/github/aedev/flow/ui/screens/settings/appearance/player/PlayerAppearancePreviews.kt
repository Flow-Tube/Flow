package io.github.aedev.flow.ui.screens.settings.appearance.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.GestureOverlayStyle
import io.github.aedev.flow.data.local.MiniBarSwipeAction
import io.github.aedev.flow.data.local.MusicPlayerBackgroundStyle
import io.github.aedev.flow.data.local.QueueSwipeAction
import io.github.aedev.flow.data.local.SliderStyle
import io.github.aedev.flow.ui.components.musicplayer.common.musicPlayerTurnsLight
import io.github.aedev.flow.ui.components.musicplayer.common.playerBackdropColors
import io.github.aedev.flow.ui.components.musicplayer.controls.ExpressivePlayerSlider
import io.github.aedev.flow.ui.components.musicplayer.controls.ExpressiveWavySlider
import io.github.aedev.flow.ui.components.musicplayer.controls.SquigglySlider
import io.github.aedev.flow.ui.components.musicplayer.controls.expressiveSliderSpec
import io.github.aedev.flow.ui.components.musicplayer.full.PlayerBackground
import io.github.aedev.flow.ui.components.musicplayer.queue.containerColor
import io.github.aedev.flow.ui.components.musicplayer.queue.contentColor
import io.github.aedev.flow.ui.components.musicplayer.queue.icon
import io.github.aedev.flow.ui.components.musicplayer.queue.labelRes
import io.github.aedev.flow.ui.components.musicplayer.queue.rowSwipe
import io.github.aedev.flow.ui.components.shared.GestureLevelHudPreview
import io.github.aedev.flow.ui.components.shared.MediaMiniBarDefaults
import io.github.aedev.flow.ui.components.shared.MediaMiniBarSwipeReveal
import io.github.aedev.flow.ui.components.shared.MiniBarReveal
import io.github.aedev.flow.ui.components.shared.icon
import io.github.aedev.flow.ui.components.shared.isDestructive
import io.github.aedev.flow.ui.components.shared.labelRes
import io.github.aedev.flow.ui.theme.PlayerGround

private const val PREVIEW_PROGRESS = 0.4f
private const val PREVIEW_DURATION = 100f
private const val SEEKBAR_PREVIEW_PROGRESS = 0.36f
private val SeekbarPreviewHeight = 44.dp
private val SeekbarPreviewInset = 8.dp
private val SeekbarTrackHeight = 4.dp
private val BackgroundPreviewHeight = 72.dp
private val GesturePreviewHeight = 184.dp

/** The real player slider in [style], parked at 40 %. */
@Composable
internal fun SliderStylePreview(style: SliderStyle) {
    val position = PREVIEW_DURATION * PREVIEW_PROGRESS
    val range = 0f..PREVIEW_DURATION
    when (style) {
        SliderStyle.SQUIGGLY -> {
            SquigglySlider(
                value = position,
                onValueChange = {},
                onValueChangeFinished = {},
                valueRange = range,
                isPlaying = true,
            )
        }

        SliderStyle.EXPRESSIVE_WAVY -> {
            ExpressiveWavySlider(
                value = position,
                onValueChange = {},
                onValueChangeFinished = {},
                valueRange = range,
                isPlaying = true,
            )
        }

        else -> {
            val spec = expressiveSliderSpec(style)
            ExpressivePlayerSlider(
                value = position,
                onValueChange = {},
                onValueChangeFinished = {},
                valueRange = range,
                trackHeight = spec.trackHeight,
                thumbHeight = spec.thumbHeight,
                thumbTrackGap = spec.thumbTrackGap,
            )
        }
    }
}

/**
 * A seek bar inside a stand-in for the video, inset by [padding] on each side. The inset animates
 * with an effects spec, which never overshoots — a spring would push the padding below zero.
 */
@Composable
internal fun SeekbarWidthPreview(padding: Dp) {
    val animated by animateDpAsState(
        targetValue = padding,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "seekbarWidthPreview",
    )
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(SeekbarPreviewHeight)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(horizontal = SeekbarPreviewInset),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = animated.coerceAtLeast(0.dp))
                    .height(SeekbarTrackHeight)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(SEEKBAR_PREVIEW_PROGRESS)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/**
 * The music player's own background in [style], painted around Flow's icon in place of album art,
 * so the preview is exactly what the player draws rather than a sketch of it. [followLightTheme]
 * is the player's light switch, so a light app theme previews the light version.
 */
@Composable
internal fun MusicBackgroundPreview(
    style: MusicPlayerBackgroundStyle,
    followLightTheme: Boolean,
) {
    val context = LocalContext.current
    val artwork = remember { "android.resource://${context.packageName}/${R.drawable.ic_launcher_foreground}" }
    val scheme = MaterialTheme.colorScheme
    val light = musicPlayerTurnsLight(style, followLightTheme, scheme.surface)
    PlayerBackground(
        thumbnailUrl = artwork,
        style = style,
        backdrop = remember(scheme, light) { playerBackdropColors(scheme.primary, scheme.tertiary, light) },
        modifier =
            Modifier
                .fillMaxWidth()
                .height(BackgroundPreviewHeight)
                .clip(MaterialTheme.shapes.medium),
    )
}

/** The volume and brightness read-out in [style], over the player's own backdrop. */
@Composable
internal fun GestureOverlayPreview(style: GestureOverlayStyle) {
    GestureLevelHudPreview(
        style = style,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(GesturePreviewHeight)
                .clip(MaterialTheme.shapes.large)
                .background(PlayerGround),
    )
}

/** What a mini bar swipe set to [action] uncovers, as the bar shows it mid-swipe towards that side. */
@Composable
internal fun MiniBarSwipePreview(
    action: MiniBarSwipeAction,
    towardsLeft: Boolean,
) {
    val reveal =
        MiniBarReveal(
            icon = action.icon,
            label = stringResource(action.labelRes),
            destructive = action.isDestructive,
        )
    Box(modifier = Modifier.fillMaxWidth().height(MediaMiniBarDefaults.Height)) {
        MediaMiniBarSwipeReveal(
            offset = { if (towardsLeft) -Float.MAX_VALUE else Float.MAX_VALUE },
            towardsLeft = reveal,
            towardsRight = reveal,
            shape = RoundedCornerShape(MediaMiniBarDefaults.CornerRadius),
        )
    }
}

/** What a queue row swiped towards that side uncovers. */
@Composable
internal fun QueueSwipePreview(
    action: QueueSwipeAction,
    towardsLeft: Boolean,
) {
    val swipe = action.rowSwipe
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(QueueSwipePreviewHeight)
                .clip(CircleShape)
                .background(swipe.containerColor)
                .padding(horizontal = QueueSwipePreviewPadding),
        horizontalArrangement = Arrangement.spacedBy(QueueSwipePreviewPadding / 2, if (towardsLeft) Alignment.End else Alignment.Start),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = swipe.icon, contentDescription = null, tint = swipe.contentColor)
        Text(text = stringResource(swipe.labelRes), style = MaterialTheme.typography.titleSmall, color = swipe.contentColor)
    }
}

private val QueueSwipePreviewHeight = 56.dp
private val QueueSwipePreviewPadding = 20.dp
