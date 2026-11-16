package io.github.aedev.flow.ui.components.musicplayer.full

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.shared.FlowConnectedToggleGroup
import io.github.aedev.flow.ui.components.shared.FlowToggleOption
import io.github.aedev.flow.ui.screens.music.MusicCastViewModel
import io.github.aedev.flow.ui.screens.playon.LocalPlayOn
import io.github.aedev.flow.ui.screens.playon.PlayOnButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerTopBar(
    playingFrom: String,
    modifier: Modifier = Modifier,
    contentColor: Color = Color.White,
    modeSwitch: (@Composable () -> Unit)? = null,
    cast: MusicCastViewModel = hiltViewModel(),
) {
    val elsewhere =
        LocalPlayOn.current
            ?.elsewhere
            ?.collectAsStateWithLifecycle()
            ?.value
            ?.name
    val castTo =
        cast.device
            .collectAsStateWithLifecycle()
            .value
            ?.friendlyName
    CenterAlignedTopAppBar(
        title = {
            if (modeSwitch != null) {
                modeSwitch()
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = stringResource(R.string.now_playing),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = contentColor.copy(alpha = 0.9f),
                    )
                    Text(
                        text = (elsewhere ?: castTo)?.let { stringResource(R.string.play_on_playing_on, it) } ?: playingFrom,
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        },
        actions = {
            MusicCastButton(cast)
            PlayOnButton(tint = contentColor)
        },
        colors =
            TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = Color.Transparent,
            ),
        modifier = modifier,
        windowInsets =
            androidx.compose.foundation.layout
                .WindowInsets(0, 0, 0, 0),
    )
}

/** The Song/Video switch that takes the title's place when it is turned on in Settings. */
@Composable
internal fun PlayerModeSwitch(
    showsVideo: Boolean,
    onSelect: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowConnectedToggleGroup(
        options =
            listOf(
                FlowToggleOption(false, stringResource(R.string.music_player_mode_song)),
                FlowToggleOption(true, stringResource(R.string.music_player_mode_video)),
            ),
        selected = showsVideo,
        onSelected = onSelect,
        modifier = modifier.width(ModeSwitchWidth),
    )
}

private val ModeSwitchWidth = 208.dp
