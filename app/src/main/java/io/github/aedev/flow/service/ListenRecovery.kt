package io.github.aedev.flow.service

import androidx.media3.common.Player

/**
 * Whether a listen carries on through playback recovery. Recovery re-prepares the song that failed,
 * or swaps a fresh stream in for it, and ending the listen there split one song into pieces that
 * each fell short of the scrobble threshold and reached the taste engine as early skips.
 */
internal object ListenRecovery {
    /** An idle player ends the listen, unless it went idle because playback failed and is retried. */
    fun endsOnIdle(failed: Boolean): Boolean = !failed

    /** The song under recovery replacing its own item is the same listen, not a new one. */
    fun continuesThroughTransition(
        reason: Int,
        nextListenId: String?,
        currentListenId: String?,
        recoveringListenId: String?,
    ): Boolean =
        reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED &&
            nextListenId != null &&
            nextListenId == currentListenId &&
            nextListenId == recoveringListenId
}
