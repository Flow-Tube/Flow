package io.github.aedev.flow.player

import android.net.Uri

/**
 * How an in-app Next or Previous changes the song (#1761). The player already holds the queue, so it
 * seeks to the entry, as the notification does. Rebuilding its playlist stops the audio and throws
 * away what is buffered, so that stays for a skip that needs something the player's items do not have.
 */
internal object MusicSkipPolicy {
    enum class Route {
        SEEK,
        NO_PLAYER,
        DRIVES_ANOTHER_DEVICE,
        QUEUE_OUT_OF_STEP,
        PLAYER_STOPPED,
        RESTORED_POSITION,
        STORAGE_UNREAD,
        SAVED_POSITION,
        DOWNLOAD_CHANGED,
        LOCAL_IN_MIXED_QUEUE,
    }

    /** What only storage can say about the song a skip goes to. */
    data class Stored(
        val downloadedFile: Uri?,
        val resumePositionMs: Long,
    )

    data class Facts(
        val targetIndex: Int,
        val targetId: String,
        val queueIds: List<String>,
        val playerIds: List<String>?,
        val playerStopped: Boolean,
        val drivesAnotherDevice: Boolean,
        val restoredPositionPending: Boolean,
        val storageRead: Boolean,
        val savedPositionMs: Long,
        val downloadMatchesPlayer: Boolean,
        val localInMixedQueue: Boolean,
    )

    fun route(facts: Facts): Route =
        when {
            facts.drivesAnotherDevice -> Route.DRIVES_ANOTHER_DEVICE
            facts.playerIds == null -> Route.NO_PLAYER
            facts.queueIds.getOrNull(facts.targetIndex) != facts.targetId || facts.queueIds != facts.playerIds -> Route.QUEUE_OUT_OF_STEP
            facts.playerStopped -> Route.PLAYER_STOPPED
            facts.restoredPositionPending -> Route.RESTORED_POSITION
            !facts.storageRead -> Route.STORAGE_UNREAD
            facts.savedPositionMs > 0L -> Route.SAVED_POSITION
            !facts.downloadMatchesPlayer -> Route.DOWNLOAD_CHANGED
            facts.localInMixedQueue -> Route.LOCAL_IN_MIXED_QUEUE
            else -> Route.SEEK
        }
}
