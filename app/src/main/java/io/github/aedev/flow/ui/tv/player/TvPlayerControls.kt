package io.github.aedev.flow.ui.tv.player

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Comment
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalToggleButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.SponsorBlockSegment
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.ui.components.shared.ChannelAvatarImage
import io.github.aedev.flow.ui.components.shared.FocusLiftDefaults
import io.github.aedev.flow.ui.components.shared.MediaPlaybackControls
import io.github.aedev.flow.ui.components.shared.MediaSeekBar
import io.github.aedev.flow.ui.components.shared.connectedButtonShapes
import io.github.aedev.flow.ui.components.shared.focusLift
import io.github.aedev.flow.ui.components.shared.videoMetadataLine
import io.github.aedev.flow.ui.components.videoplayer.controls.PlayerControlActions
import io.github.aedev.flow.ui.components.videoplayer.controls.ScrimEdge
import io.github.aedev.flow.ui.components.videoplayer.controls.playerEdgeScrim
import io.github.aedev.flow.ui.screens.playon.PlayOnButton
import io.github.aedev.flow.ui.theme.PlayerScrimContent
import io.github.aedev.flow.ui.theme.PlayerScrimContentSecondary
import io.github.aedev.flow.ui.theme.PlayerScrimEdgeGradient
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens
import io.github.aedev.flow.utils.formatDuration
import org.schabi.newpipe.extractor.stream.StreamSegment

private val AvatarSize = 28.dp
private val ChannelNameMaxWidth = 240.dp
private val ChannelButtonPadding = PaddingValues(start = 6.dp, end = 16.dp)
private val UtilityButtonWidth = 64.dp
private val UtilityButtonHeight = 56.dp
private val UtilityIconSize = 24.dp
private val TransportWidth = 320.dp
private val ScrimLeadIn = 120.dp
private val UpNextLeadIn = 48.dp
private val SeekLeadIn = 64.dp

/** The panels and toggles the TV control row opens; the play actions come in [PlayerControlActions]. */
internal class TvPlayerUtilityActions(
    val onCaptions: () -> Unit,
    val onSave: () -> Unit,
    val onComments: () -> Unit,
    val onLiveChat: () -> Unit,
    val onInfo: () -> Unit,
    val onSettings: () -> Unit,
)

/**
 * TV's player controls, built from the phone player's parts: its edge scrim behind everything, the
 * title block, the current chapter and the phone's seek bar, then the music player's M3E transport and
 * a connected group of tonal buttons for the panels.
 */
@Composable
internal fun BoxScope.TvPlayerControls(
    visible: Boolean,
    seekOnly: Boolean,
    video: Video,
    isPlaying: Boolean,
    isBuffering: Boolean,
    isLive: Boolean,
    subtitlesAvailable: Boolean,
    subtitlesOn: Boolean,
    positionMs: () -> Long,
    durationMs: () -> Long,
    scrubTargetMs: Long?,
    bufferedFraction: Float,
    chapters: List<StreamSegment>,
    sponsorSegments: List<SponsorBlockSegment>,
    sponsorColors: Map<String, Color>,
    playActions: PlayerControlActions,
    utilityActions: TvPlayerUtilityActions,
    seekBarFocusRequester: FocusRequester,
    playPauseFocusRequester: FocusRequester,
    onSeekBarFocusChanged: (Boolean) -> Unit,
    upNextShown: Boolean,
    upNext: @Composable () -> Unit,
    onChannelClick: () -> Unit,
) {
    val dimens = LocalTvDimens.current
    val channelFocusRequester = remember { FocusRequester() }
    val channelLinked = video.channelId.isNotBlank()
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = fadeIn(motion.defaultEffectsSpec()) + slideInVertically(motion.defaultSpatialSpec()) { it / 4 },
        exit = fadeOut(motion.fastEffectsSpec()) + slideOutVertically(motion.fastSpatialSpec()) { it / 4 },
    ) {
        // The phone player's edge scrim (D4: the one gradient the redesign allows, in the player). It starts
        // well above the title so the text sits on enough of it over a bright frame. The up-next row needs
        // that height for its cards, so while it is open the title steps aside. Seeking with the controls
        // hidden shows the seek bar alone, on a shallow scrim (#998).
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .playerEdgeScrim(ScrimEdge.Bottom, PlayerScrimEdgeGradient)
                    .padding(
                        start = dimens.overscanHorizontal,
                        end = dimens.overscanHorizontal,
                        top =
                            when {
                                seekOnly -> SeekLeadIn
                                upNextShown -> UpNextLeadIn
                                else -> ScrimLeadIn
                            },
                        bottom = dimens.overscanVertical,
                    ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!upNextShown) {
                AnimatedVisibility(
                    visible = !seekOnly,
                    enter = fadeIn(motion.defaultEffectsSpec()) + expandVertically(motion.defaultSpatialSpec()),
                    exit = fadeOut(motion.fastEffectsSpec()) + shrinkVertically(motion.fastSpatialSpec()),
                ) {
                    TvPlayerTitle(
                        video = video,
                        isLive = isLive,
                        onChannelClick = onChannelClick.takeIf { channelLinked },
                        channelFocusRequester = channelFocusRequester,
                    )
                }
            }
            val displayedPositionMs = { scrubTargetMs ?: positionMs() }
            val chapterTitle by remember(chapters) {
                derivedStateOf {
                    val positionSeconds = displayedPositionMs() / 1_000L
                    chapters.lastOrNull { it.startTimeSeconds <= positionSeconds }?.title
                }
            }
            chapterTitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelLarge,
                    color = PlayerScrimContentSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!isLive) {
                MediaSeekBar(
                    value = {
                        val duration = durationMs()
                        if (duration > 0) (displayedPositionMs().toFloat() / duration).coerceIn(0f, 1f) else 0f
                    },
                    onValueChange = {},
                    chapters = chapters,
                    sponsorSegments = sponsorSegments,
                    sponsorColors = sponsorColors,
                    duration = durationMs(),
                    bufferedValue = bufferedFraction,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(seekBarFocusRequester)
                            .onFocusChanged { onSeekBarFocusChanged(it.isFocused) }
                            // Play on lines up better with the full-width bar, so Up would skip the channel.
                            .focusProperties { if (channelLinked && !upNextShown) up = channelFocusRequester },
                )
                TvPlayerTimes(positionMs = displayedPositionMs, durationMs = durationMs, scrubbing = scrubTargetMs != null)
            }
            AnimatedVisibility(
                visible = !seekOnly,
                enter = fadeIn(motion.defaultEffectsSpec()) + expandVertically(motion.defaultSpatialSpec()),
                exit = fadeOut(motion.fastEffectsSpec()) + shrinkVertically(motion.fastSpatialSpec()),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MediaPlaybackControls(
                            isPlaying = isPlaying,
                            isBuffering = isBuffering,
                            onPreviousClick = playActions.onPrevious,
                            onPlayPauseToggle = playActions.onPlayPause,
                            onNextClick = playActions.onNext,
                            playPauseFocusRequester = playPauseFocusRequester,
                            modifier = Modifier.width(TransportWidth),
                        )
                        Spacer(Modifier.weight(1f))
                        TvPlayerUtilities(
                            isLive = isLive,
                            subtitlesAvailable = subtitlesAvailable,
                            subtitlesOn = subtitlesOn,
                            actions = utilityActions,
                        )
                    }
                    // The row opens on Down from the controls, so it is composed only once someone asks for it.
                    if (upNextShown) {
                        upNext()
                    } else {
                        Text(
                            text = stringResource(R.string.up_next),
                            style = MaterialTheme.typography.labelLarge,
                            color = PlayerScrimContentSecondary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The title, then the channel as one button (OK opens its page) beside the views and date. Until the
 * video's details name its channel, the channel is plain text in that line instead.
 */
@Composable
private fun TvPlayerTitle(
    video: Video,
    isLive: Boolean,
    onChannelClick: (() -> Unit)?,
    channelFocusRequester: FocusRequester,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = video.title,
            style = MaterialTheme.typography.titleLarge,
            color = PlayerScrimContent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val showsChannel = onChannelClick == null
            if (onChannelClick != null) {
                TvPlayerChannelButton(video = video, onClick = onChannelClick, focusRequester = channelFocusRequester)
            }
            Text(
                text =
                    if (isLive) {
                        listOf(video.channelName.takeIf { showsChannel }.orEmpty(), stringResource(R.string.status_live))
                            .filter(String::isNotBlank)
                            .joinToString(" • ")
                    } else {
                        videoMetadataLine(video = video, isUpcoming = false, includeChannel = showsChannel)
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = PlayerScrimContentSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            PlayOnButton(tint = PlayerScrimContent)
        }
    }
}

@Composable
private fun TvPlayerChannelButton(
    video: Video,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
) {
    val interactionSource = remember { MutableInteractionSource() }
    FilledTonalButton(
        onClick = onClick,
        interactionSource = interactionSource,
        contentPadding = ChannelButtonPadding,
        modifier =
            Modifier
                .focusRequester(focusRequester)
                .focusLift(interactionSource, FocusLiftDefaults.SMALL_SCALE),
    ) {
        ChannelAvatarImage(
            url = video.channelThumbnailUrl,
            contentDescription = null,
            modifier =
                Modifier
                    .size(AvatarSize)
                    .clip(CircleShape),
        )
        Text(
            text = video.channelName,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp).widthIn(max = ChannelNameMaxWidth),
        )
    }
}

/** Elapsed and total time, recomposed once a second at most. */
@Composable
private fun TvPlayerTimes(
    positionMs: () -> Long,
    durationMs: () -> Long,
    scrubbing: Boolean,
) {
    val positionSeconds by remember { derivedStateOf { (positionMs() / 1_000L).toInt() } }
    val durationSeconds by remember { derivedStateOf { (durationMs() / 1_000L).toInt() } }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = formatDuration(positionSeconds.coerceAtLeast(0)),
            style = MaterialTheme.typography.labelLarge,
            color = if (scrubbing) MaterialTheme.colorScheme.primary else PlayerScrimContent,
        )
        Text(
            text = formatDuration(durationSeconds.coerceAtLeast(0)),
            style = MaterialTheme.typography.labelLarge,
            color = PlayerScrimContentSecondary,
        )
    }
}

/** The panels as one connected group of tonal M3E toggle buttons; captions stay checked while they show. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TvPlayerUtilities(
    isLive: Boolean,
    subtitlesAvailable: Boolean,
    subtitlesOn: Boolean,
    actions: TvPlayerUtilityActions,
) {
    val utilities =
        buildList {
            if (subtitlesAvailable) {
                add(
                    TvPlayerUtility(Icons.Outlined.ClosedCaption, R.string.filter_subtitles, actions.onCaptions, subtitlesOn),
                )
            }
            add(TvPlayerUtility(Icons.AutoMirrored.Outlined.PlaylistAdd, R.string.save, actions.onSave))
            if (isLive) {
                add(TvPlayerUtility(Icons.Outlined.Forum, R.string.live_chat, actions.onLiveChat))
            } else {
                add(TvPlayerUtility(Icons.AutoMirrored.Outlined.Comment, R.string.comments, actions.onComments))
            }
            add(TvPlayerUtility(Icons.Outlined.Info, R.string.description, actions.onInfo))
            add(TvPlayerUtility(Icons.Outlined.Settings, R.string.player_settings, actions.onSettings))
        }
    Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        utilities.forEachIndexed { index, utility ->
            TvPlayerUtilityButton(utility = utility, shapes = connectedButtonShapes(index, utilities.size))
        }
    }
}

private class TvPlayerUtility(
    val icon: ImageVector,
    @param:StringRes val labelRes: Int,
    val onClick: () -> Unit,
    val checked: Boolean = false,
)

/** One button of the group; the focused one names itself beneath, so each stays a single icon. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TvPlayerUtilityButton(
    utility: TvPlayerUtility,
    shapes: ToggleButtonShapes,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val label = stringResource(utility.labelRes)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        ToggleButton(
            checked = utility.checked,
            onCheckedChange = { utility.onClick() },
            shapes = shapes,
            colors = FilledTonalToggleButtonDefaults.filledTonalToggleButtonColors(),
            contentPadding = PaddingValues(0.dp),
            interactionSource = interactionSource,
            modifier = Modifier.size(width = UtilityButtonWidth, height = UtilityButtonHeight),
        ) {
            Icon(imageVector = utility.icon, contentDescription = label, modifier = Modifier.size(UtilityIconSize))
        }
        Text(
            text = if (focused) label else "",
            style = MaterialTheme.typography.labelMedium,
            color = PlayerScrimContent,
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
