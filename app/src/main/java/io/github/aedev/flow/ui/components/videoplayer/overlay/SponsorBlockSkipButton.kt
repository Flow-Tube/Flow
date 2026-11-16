package io.github.aedev.flow.ui.components.videoplayer.overlay

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.SponsorBlockAction
import io.github.aedev.flow.data.local.SponsorBlockSkipButtonStyle
import io.github.aedev.flow.data.model.SponsorBlockSegment
import io.github.aedev.flow.ui.theme.PlayerScrim
import io.github.aedev.flow.ui.theme.PlayerScrimContent
import io.github.aedev.flow.ui.theme.PlayerScrimPanel
import io.github.aedev.flow.utils.sponsorCategoryLabelRes
import kotlinx.coroutines.delay

private const val SB_SKIP_DIM_DELAY_MS = 5_000L
private const val SB_SKIP_DIMMED_ALPHA = 0.45f
private val LabelledPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp)
private val LabelledIconSize = 16.dp
private val IconOnlyPadding = PaddingValues(10.dp)
private val IconOnlyIconSize = 20.dp

/**
 * Overlay button that lets the user manually skip a SponsorBlock segment.
 */
@Composable
fun SponsorBlockSkipButton(
    sponsorSegments: List<SponsorBlockSegment>,
    currentPositionMs: Long,
    categoryActions: Map<String, SponsorBlockAction>,
    controlsVisible: Boolean,
    playbackEnded: Boolean,
    onSkipClick: (endPositionMs: Long) -> Unit,
    modifier: Modifier = Modifier,
    skippedOnceUuids: Set<String> = emptySet(),
    style: SponsorBlockSkipButtonStyle = SponsorBlockSkipButtonStyle.FULL,
) {
    var skippedUuids by remember(sponsorSegments) { mutableStateOf(emptySet<String>()) }

    val activeSegment =
        remember(
            sponsorSegments,
            currentPositionMs,
            skippedUuids,
            categoryActions,
            playbackEnded,
            skippedOnceUuids,
        ) {
            findActiveManualSponsorSegment(
                sponsorSegments = sponsorSegments,
                currentPositionMs = currentPositionMs,
                skippedUuids = skippedUuids,
                categoryActions = categoryActions,
                playbackEnded = playbackEnded,
                skippedOnceUuids = skippedOnceUuids,
            )
        }

    var displaySegment by remember { mutableStateOf<SponsorBlockSegment?>(null) }
    LaunchedEffect(activeSegment) {
        if (activeSegment != null) displaySegment = activeSegment
    }

    var isDimmed by remember { mutableStateOf(false) }
    LaunchedEffect(activeSegment?.uuid, controlsVisible) {
        if (activeSegment == null || controlsVisible) {
            isDimmed = false
        } else {
            isDimmed = false
            delay(SB_SKIP_DIM_DELAY_MS)
            isDimmed = true
        }
    }

    val buttonAlpha =
        animateFloatAsState(
            targetValue = if (isDimmed) SB_SKIP_DIMMED_ALPHA else 1f,
            animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
            label = "sbSkipAlpha",
        )

    AnimatedVisibility(
        visible = activeSegment != null,
        enter =
            slideInHorizontally(MaterialTheme.motionScheme.fastSpatialSpec(), initialOffsetX = { it }) +
                fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
        exit =
            slideOutHorizontally(MaterialTheme.motionScheme.fastSpatialSpec(), targetOffsetX = { it }) +
                fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()),
        modifier = modifier,
    ) {
        val seg = displaySegment ?: return@AnimatedVisibility
        val categoryRes = sponsorCategoryLabelRes(seg.category)
        val skipLabel =
            when {
                seg.isPoint -> stringResource(R.string.sb_jump_to_highlight)
                categoryRes != null -> stringResource(R.string.sb_skip_segment, stringResource(categoryRes))
                else -> stringResource(R.string.sb_manual_skip)
            }
        val visibleLabel =
            when (style) {
                SponsorBlockSkipButtonStyle.FULL -> skipLabel
                SponsorBlockSkipButtonStyle.COMPACT -> stringResource(if (seg.isPoint) R.string.sb_jump else R.string.sb_manual_skip)
                SponsorBlockSkipButtonStyle.ICON_ONLY -> null
            }
        Surface(
            onClick = {
                skippedUuids = skippedUuids + seg.uuid
                onSkipClick((seg.endTime * 1000L).toLong())
            },
            color = PlayerScrimPanel,
            contentColor = PlayerScrimContent,
            shape = CircleShape,
            tonalElevation = 0.dp,
            modifier = Modifier.graphicsLayer { alpha = buttonAlpha.value },
        ) {
            Row(
                modifier =
                    Modifier
                        .clearAndSetSemantics { contentDescription = skipLabel }
                        .padding(if (visibleLabel == null) IconOnlyPadding else LabelledPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                visibleLabel?.let {
                    Text(
                        text = it,
                        color = PlayerScrimContent,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.SkipNext,
                    contentDescription = null,
                    tint = PlayerScrimContent,
                    modifier = Modifier.size(if (visibleLabel == null) IconOnlyIconSize else LabelledIconSize),
                )
            }
        }
    }
}
