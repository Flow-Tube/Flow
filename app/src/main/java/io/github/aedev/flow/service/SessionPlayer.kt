package io.github.aedev.flow.service

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.aedev.flow.player.APP_CONTROLLER_HINT
import io.github.aedev.flow.player.PlaybackTarget
import io.github.aedev.flow.player.RemotePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The music session's player. While the app follows another device's music (Play on), it reports
 * that device's play state and position, and the notification, lock screen, headset and car drive
 * that device; the app's own controller still reaches the player here. [onResume] runs before a
 * resume here, which turns offload back on. A Next or Previous from those buttons that changes song
 * goes to [onSkip], which holds the old song for the fade like the app's own buttons, and falls back
 * to the player's seek when it returns false (#2163).
 */
@OptIn(UnstableApi::class)
internal class SessionPlayer(
    player: Player,
    private val requester: () -> MediaSession.ControllerInfo?,
    private val onSkip: (index: Int, mediaId: String) -> Boolean,
    private val onResume: () -> Unit,
) : ForwardingSimpleBasePlayer(player) {
    private var lyricLine: String? = null

    /** Republishes the state whenever the followed session changes, for as long as [scope] lives. */
    fun follow(scope: CoroutineScope) {
        scope.launch { PlaybackTarget.followed.collect { invalidateState() } }
    }

    /** The driven device, when the request comes from outside the app while following it. */
    private fun driven(): RemotePlayer? =
        PlaybackTarget.current?.takeIf {
            PlaybackTarget.followed.value != null && requester()?.connectionHints?.getBoolean(APP_CONTROLLER_HINT) == false
        }

    /** Reports [line] as the title: HyperOS's shade and lock screen read only the title and artist keys. */
    fun setLyricLine(line: String?) {
        if (lyricLine == line) return
        lyricLine = line
        invalidateState()
    }

    override fun getState(): State {
        val state = super.getState()
        val followed = PlaybackTarget.followed.value
        if (followed == null || state.playlist.isEmpty()) return state.withLyricLine()
        return state
            .buildUpon()
            .setPlaybackState(STATE_READY)
            .setPlayWhenReady(followed.playing, PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setContentPositionMs { followed.positionAt(System.currentTimeMillis()) }
            .build()
    }

    private fun State.withLyricLine(): State {
        val line = lyricLine ?: return this
        if (playlist.isEmpty()) return this
        val metadata =
            currentMetadata
                .buildUpon()
                .setTitle(line)
                .setArtist(sessionLyricArtist(currentMetadata.title, currentMetadata.artist))
                .build()
        return buildUpon().setPlaylist(timeline, currentTracks, metadata).build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        driven()?.let { remote ->
            remote.command(if (playWhenReady) "play" else "pause")
            return Futures.immediateVoidFuture()
        }
        if (playWhenReady) onResume()
        return super.handleSetPlayWhenReady(playWhenReady)
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        val remote = driven()
        if (remote == null) {
            val skipTo =
                sessionSkipIndex(seekCommand, mediaItemIndex, player.currentMediaItemIndex, player.mediaItemCount, player.playWhenReady)
            if (skipTo != null && onSkip(skipTo, player.getMediaItemAt(skipTo).mediaId)) return Futures.immediateVoidFuture()
            return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        }
        when (seekCommand) {
            COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> remote.command("next")
            COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> remote.command("previous")
            COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM -> remote.command("seek", positionMs)
            else -> remote.play(remote.queue, mediaItemIndex, music = true)
        }
        return Futures.immediateVoidFuture()
    }
}

private val songSkips =
    setOf(
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
    )

/**
 * The queue slot a Next or Previous from outside the app moves to, when it changes song while the
 * music plays; null when the player's own seek is right (a Previous that restarts the song, a seek
 * within it, or nothing playing).
 */
internal fun sessionSkipIndex(
    seekCommand: Int,
    targetIndex: Int,
    currentIndex: Int,
    itemCount: Int,
    playWhenReady: Boolean,
): Int? = targetIndex.takeIf { playWhenReady && seekCommand in songSkips && it != currentIndex && it in 0 until itemCount }
