package io.github.aedev.flow.ui.components.musicplayer.sheet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.MiniBarSwipeAction
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.ui.components.shared.MediaMiniBarSwipeReveal
import io.github.aedev.flow.ui.components.shared.MiniBarReveal
import io.github.aedev.flow.ui.components.shared.MiniBarSwipeCommit
import io.github.aedev.flow.ui.components.shared.MiniBarSwipeMotion
import io.github.aedev.flow.ui.components.shared.icon
import io.github.aedev.flow.ui.components.shared.isDestructive
import io.github.aedev.flow.ui.components.shared.labelRes

/** The music bar acts on Close, Next and Previous; any other action set for the swipe closes it. */
private val MiniBarSwipeAction.forMusic: MiniBarSwipeAction
    get() = if (appliesToMusic) this else MiniBarSwipeAction.CLOSE

private fun neighbourTrack(
    action: MiniBarSwipeAction,
    queue: List<MusicTrack>,
    index: Int,
): MusicTrack? =
    when (action) {
        MiniBarSwipeAction.NEXT -> queue.getOrNull(index + 1)
        MiniBarSwipeAction.PREVIOUS -> if (index > 0) queue.getOrNull(index - 1) else null
        else -> null
    }

internal fun musicBarSwipeCommit(
    action: MiniBarSwipeAction,
    onClose: () -> Unit,
): MiniBarSwipeCommit? {
    val queue = EnhancedMusicPlayerManager.queue.value
    val index = EnhancedMusicPlayerManager.currentQueueIndex.value
    return when (val music = action.forMusic) {
        MiniBarSwipeAction.CLOSE -> {
            MiniBarSwipeCommit(MiniBarSwipeMotion.FlyOff, onClose)
        }

        else -> {
            neighbourTrack(music, queue, index)?.let {
                MiniBarSwipeCommit(MiniBarSwipeMotion.FlyThrough) {
                    if (music == MiniBarSwipeAction.NEXT) {
                        EnhancedMusicPlayerManager.playNext()
                    } else {
                        EnhancedMusicPlayerManager.playPreviousTrack()
                    }
                }
            }
        }
    }
}

/** What swiping the music bar uncovers: the next or previous track, or Close. */
@Composable
internal fun MusicBarSwipeReveal(
    offset: () -> Float,
    swipeLeftAction: MiniBarSwipeAction,
    swipeRightAction: MiniBarSwipeAction,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    val queue by EnhancedMusicPlayerManager.queue.collectAsStateWithLifecycle()
    val index by EnhancedMusicPlayerManager.currentQueueIndex.collectAsStateWithLifecycle()
    MediaMiniBarSwipeReveal(
        offset = offset,
        towardsLeft = musicBarReveal(swipeLeftAction.forMusic, queue, index),
        towardsRight = musicBarReveal(swipeRightAction.forMusic, queue, index),
        shape = shape,
        modifier = modifier,
    )
}

@Composable
private fun musicBarReveal(
    action: MiniBarSwipeAction,
    queue: List<MusicTrack>,
    index: Int,
): MiniBarReveal {
    val track = neighbourTrack(action, queue, index)
    val label =
        when {
            track != null -> track.title
            action == MiniBarSwipeAction.NEXT -> stringResource(R.string.mini_bar_nothing_next)
            action == MiniBarSwipeAction.PREVIOUS -> stringResource(R.string.mini_bar_nothing_previous)
            else -> stringResource(action.labelRes)
        }
    return MiniBarReveal(
        icon = action.icon,
        label = label,
        destructive = action.isDestructive,
        enabled = action == MiniBarSwipeAction.CLOSE || track != null,
        peekImageUrl = track?.listThumbnailUrl,
    )
}
