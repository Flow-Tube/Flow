package io.github.aedev.flow.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * The connection every Flow video session gives a controller. Media3 hands a controller it can't
 * verify, such as the service relaying an earbud tap, read-only player commands, and then drops its
 * play/pause key without a word (#1922). Play, pause and seek are accepted from any of them, as the
 * music session always has; extra session commands stay with the caller.
 */
@OptIn(UnstableApi::class)
internal fun mediaButtonConnection(
    session: MediaSession,
    controller: MediaSession.ControllerInfo,
): MediaSession.ConnectionResult.AcceptedResultBuilder =
    MediaSession.ConnectionResult
        .AcceptedResultBuilder(session, controller)
        .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)

/** For a session with nothing of its own to add, such as the Shorts reel's. */
internal object MediaButtonSessionCallback : MediaSession.Callback {
    override fun onConnectAsync(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): ListenableFuture<MediaSession.ConnectionResult> = Futures.immediateFuture(mediaButtonConnection(session, controller).build())
}
