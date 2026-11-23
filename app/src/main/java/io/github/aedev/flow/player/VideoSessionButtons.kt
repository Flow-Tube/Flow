package io.github.aedev.flow.player

import android.content.Context
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.aedev.flow.R

/** What the video notification's repeat and shuffle buttons show and do. */
internal object VideoSessionControls {
    data class State(
        val videoLooping: Boolean,
        val queueLooping: Boolean,
        val queueShuffled: Boolean,
        val queueSize: Int,
    ) {
        val hasQueue: Boolean get() = queueSize > 1

        val repeat: RepeatMode
            get() = videoRepeatMode(queueLoop = queueLooping && hasQueue, videoLoop = videoLooping)
    }

    data class LoopTarget(
        val videoLoop: Boolean,
        val queueLoop: Boolean,
    )

    /** Off, then the whole queue, then this video; a single video goes straight to looping itself. */
    fun nextLoop(state: State): LoopTarget {
        val next = state.repeat.next().let { if (it == RepeatMode.ALL && !state.hasQueue) RepeatMode.ONE else it }
        return LoopTarget(videoLoop = next == RepeatMode.ONE, queueLoop = next == RepeatMode.ALL)
    }
}

/**
 * Repeat and shuffle on the video session, for the media card and older notifications. They are
 * session commands because the queue lives in the app: the player only ever holds the current video.
 */
@OptIn(UnstableApi::class)
internal class VideoSessionButtons(
    private val context: Context,
    private val currentState: () -> VideoSessionControls.State,
    private val onLoop: (VideoSessionControls.LoopTarget) -> Unit,
    private val onShuffle: (Boolean) -> Unit,
) {
    val callback: MediaSession.Callback =
        object : MediaSession.Callback {
            // Not super.onConnect: in Media3 1.11 it is an empty placeholder.
            override fun onConnectAsync(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
            ): ListenableFuture<MediaSession.ConnectionResult> {
                val result = mediaButtonConnection(session, controller)
                if (controller.isTrusted) {
                    result.setAvailableSessionCommands(
                        MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                            .buildUpon()
                            .add(REPEAT)
                            .add(SHUFFLE)
                            .build(),
                    )
                }
                return Futures.immediateFuture(result.build())
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle,
            ): ListenableFuture<SessionResult> {
                val state = currentState()
                when (customCommand.customAction) {
                    REPEAT.customAction -> onLoop(VideoSessionControls.nextLoop(state))
                    SHUFFLE.customAction -> if (state.hasQueue) onShuffle(!state.queueShuffled)
                    else -> return super.onCustomCommand(session, controller, customCommand, args)
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
        }

    fun buttons(state: VideoSessionControls.State): List<CommandButton> {
        val (repeatIcon, repeatIconRes) =
            when (state.repeat) {
                RepeatMode.ONE -> CommandButton.ICON_REPEAT_ONE to R.drawable.ic_repeat_one_on
                RepeatMode.ALL -> CommandButton.ICON_REPEAT_ALL to R.drawable.ic_repeat_on
                RepeatMode.OFF -> CommandButton.ICON_REPEAT_OFF to R.drawable.ic_repeat
            }
        val repeat =
            CommandButton
                .Builder(repeatIcon)
                .setDisplayName(context.getString(R.string.repeat))
                .setCustomIconResId(repeatIconRes)
                .setSessionCommand(REPEAT)
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build()
        if (!state.hasQueue) return listOf(repeat)
        val shuffle =
            CommandButton
                .Builder(if (state.queueShuffled) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF)
                .setDisplayName(context.getString(R.string.shuffle))
                .setCustomIconResId(if (state.queueShuffled) R.drawable.ic_shuffle_on else R.drawable.ic_shuffle)
                .setSessionCommand(SHUFFLE)
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build()
        return listOf(repeat, shuffle)
    }

    private companion object {
        val REPEAT = SessionCommand("flow.video.REPEAT", Bundle.EMPTY)
        val SHUFFLE = SessionCommand("flow.video.SHUFFLE", Bundle.EMPTY)
    }
}
