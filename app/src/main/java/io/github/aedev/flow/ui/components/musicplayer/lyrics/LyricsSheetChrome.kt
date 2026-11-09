package io.github.aedev.flow.ui.components.musicplayer.lyrics

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.LYRICS_ALIGN_LEFT
import io.github.aedev.flow.data.local.LYRICS_ALIGN_RIGHT
import io.github.aedev.flow.ui.components.PlayingWaveform
import kotlinx.coroutines.delay

internal fun lyricsTextAlignFor(pref: String): TextAlign =
    when (pref) {
        LYRICS_ALIGN_LEFT -> TextAlign.Left
        LYRICS_ALIGN_RIGHT -> TextAlign.Right
        else -> TextAlign.Center
    }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LyricsTrackPill(
    title: String,
    artist: String,
    artworkUrl: String,
    isPlaying: Boolean,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = title to artist,
        transitionSpec = {
            (fadeIn(tween(280)) + scaleIn(initialScale = 0.92f, animationSpec = tween(280)))
                .togetherWith(fadeOut(tween(200)))
        },
        label = "lyricsPillTrack",
        modifier = modifier,
    ) { (pillTitle, pillArtist) ->
        Row(
            modifier =
                Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .animateContentSize()
                    .padding(start = 6.dp, end = 18.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape),
            ) {
                AsyncImage(
                    model = artworkUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    text = pillTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = pillArtist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isLoading) {
                LoadingIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            } else if (isPlaying) {
                PlayingWaveform(
                    color = MaterialTheme.colorScheme.primary,
                    barCount = 3,
                    barWidth = 2.5.dp,
                    barSpacing = 1.5.dp,
                    staggerMillis = 120,
                )
            }
        }
    }
}

@Composable
internal fun LyricsBottomBar(
    isPlaying: Boolean,
    isBuffering: Boolean,
    onBack: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val backInteraction = remember { MutableInteractionSource() }
        val backPressed by backInteraction.collectIsPressedAsState()
        val backScale by animateFloatAsState(
            targetValue = if (backPressed) 0.85f else 1f,
            animationSpec =
                spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
            label = "lyricsBackScale",
        )
        FilledTonalIconButton(
            onClick = onBack,
            modifier =
                Modifier
                    .size(48.dp)
                    .graphicsLayer {
                        scaleX = backScale
                        scaleY = backScale
                    },
            shape = CircleShape,
            colors =
                IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            interactionSource = backInteraction,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.close),
                modifier = Modifier.size(22.dp),
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        val haptics = LocalHapticFeedback.current
        val playCorner by animateDpAsState(
            targetValue = if (isPlaying) 18.dp else 32.dp,
            animationSpec = spring(stiffness = Spring.StiffnessLow),
            label = "lyricsPlayCorner",
        )
        Box(
            modifier =
                Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(playCorner))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                        onTogglePlayPause()
                    },
            contentAlignment = Alignment.Center,
        ) {
            if (isBuffering) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.5.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                AnimatedContent(targetState = isPlaying, label = "lyricsPlayIcon") { playing ->
                    Icon(
                        imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription =
                            stringResource(if (playing) R.string.pause else R.string.play),
                        modifier = Modifier.size(30.dp),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        FilledTonalIconButton(
            onClick = onMenu,
            modifier = Modifier.size(48.dp),
            shape = CircleShape,
            colors =
                IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
        ) {
            Icon(
                imageVector = Icons.Outlined.MoreVert,
                contentDescription = stringResource(R.string.more_options),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** Shown while the user has scrolled away from the sung line; brings the list back to it. */
@Composable
internal fun LyricsResyncButton(
    visible: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(160)),
        exit = fadeOut(tween(160)),
        modifier = modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = Color.Black.copy(alpha = 0.42f),
            contentColor = accent,
        ) {
            IconButton(onClick = onClick) {
                Icon(
                    imageVector = Icons.Outlined.Sync,
                    contentDescription = stringResource(R.string.ui_sync_lyrics),
                )
            }
        }
    }
}

/** Names the lyrics source for three seconds after it changes, then fades out. */
@Composable
internal fun LyricsProviderLabel(
    providerName: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    if (providerName.isBlank()) return
    var showProviderName by remember(providerName) { mutableStateOf(true) }
    val providerAlpha by animateFloatAsState(
        targetValue = if (showProviderName) 1f else 0f,
        animationSpec = tween(durationMillis = 600),
        label = "providerNameAlpha",
    )

    LaunchedEffect(providerName) {
        showProviderName = true
        delay(3000)
        showProviderName = false
    }

    if (providerAlpha > 0f) {
        Text(
            text = providerName,
            color = accent.copy(alpha = 0.6f * providerAlpha),
            style = MaterialTheme.typography.labelSmall,
            modifier = modifier,
        )
    }
}

/** The dark backdrop lyrics draw on, from the artwork's base colour. */
internal fun lyricsBackdrop(base: Color): Color = lerp(base, Color.Black, 0.3f)
