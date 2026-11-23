package io.github.aedev.flow.service

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import io.github.aedev.flow.R

@OptIn(UnstableApi::class)
internal class MusicNotificationProvider(
    private val context: Context,
) : DefaultMediaNotificationProvider(context) {
    init {
        setSmallIcon(R.drawable.ic_notification_logo)
    }

    override fun getMediaButtons(
        session: MediaSession,
        playerCommands: Player.Commands,
        customLayout: ImmutableList<CommandButton>,
        showPauseButton: Boolean,
    ): ImmutableList<CommandButton> {
        val playPauseButton =
            CommandButton
                .Builder(if (showPauseButton) CommandButton.ICON_PAUSE else CommandButton.ICON_PLAY)
                .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
                .setCustomIconResId(if (showPauseButton) R.drawable.ic_pause else R.drawable.ic_play)
                .setDisplayName(context.getString(if (showPauseButton) R.string.pause else R.string.play))
                .setEnabled(playerCommands.contains(Player.COMMAND_PLAY_PAUSE))
                .build()

        val prevButton =
            CommandButton
                .Builder(CommandButton.ICON_PREVIOUS)
                .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS)
                .setCustomIconResId(R.drawable.ic_previous)
                .setDisplayName(context.getString(R.string.previous))
                .setEnabled(playerCommands.contains(Player.COMMAND_SEEK_TO_PREVIOUS))
                .build()

        val nextButton =
            CommandButton
                .Builder(CommandButton.ICON_NEXT)
                .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT)
                .setCustomIconResId(R.drawable.ic_next)
                .setDisplayName(context.getString(R.string.next))
                .setEnabled(playerCommands.contains(Player.COMMAND_SEEK_TO_NEXT))
                .build()

        var shuffleButton: CommandButton? = null
        var repeatButton: CommandButton? = null
        var likeButton: CommandButton? = null
        var closeButton: CommandButton? = null

        for (button in customLayout) {
            if (button.sessionCommand?.customAction == Media3MusicService.ACTION_TOGGLE_SHUFFLE) {
                shuffleButton = button
            } else if (button.sessionCommand?.customAction == Media3MusicService.ACTION_TOGGLE_REPEAT) {
                repeatButton = button
            } else if (button.sessionCommand?.customAction == Media3MusicService.ACTION_TOGGLE_LIKE) {
                likeButton = button
            } else if (button.sessionCommand?.customAction == Media3MusicService.ACTION_STOP) {
                closeButton = button
            }
        }

        val builder = ImmutableList.builder<CommandButton>()

        likeButton?.let { builder.add(it) }
        shuffleButton?.let { builder.add(it) }
        builder.add(prevButton)
        builder.add(playPauseButton)
        builder.add(nextButton)
        repeatButton?.let { builder.add(it) }
        closeButton?.let { builder.add(it) }

        return builder.build()
    }
}
