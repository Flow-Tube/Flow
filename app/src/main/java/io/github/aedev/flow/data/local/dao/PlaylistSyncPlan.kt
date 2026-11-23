package io.github.aedev.flow.data.local.dao

/** The writes that turn a playlist's stored rows into a wanted order. Rows not named here are left as they are. */
internal data class PlaylistSyncPlan(
    val remove: List<String>,
    val insert: List<PlaylistVideoPosition>,
    val move: List<PlaylistVideoPosition>,
) {
    val isEmpty: Boolean get() = remove.isEmpty() && insert.isEmpty() && move.isEmpty()
}

/**
 * Plans making a playlist hold exactly [wantedIds], at positions 0 to n-1. A video already stored
 * keeps its row, and with it the date it was added; only its position is written, and only when it
 * differs. Only videos the playlist does not hold yet are inserted.
 */
internal fun planPlaylistSync(
    current: List<PlaylistVideoPosition>,
    wantedIds: List<String>,
): PlaylistSyncPlan {
    val wanted = wantedIds.distinct()
    val stored = current.associate { it.videoId to it.position }
    val insert = ArrayList<PlaylistVideoPosition>()
    val move = ArrayList<PlaylistVideoPosition>()
    wanted.forEachIndexed { index, videoId ->
        val position = index.toLong()
        when (stored[videoId]) {
            null -> insert += PlaylistVideoPosition(videoId, position)
            position -> Unit
            else -> move += PlaylistVideoPosition(videoId, position)
        }
    }
    val wantedSet = wanted.toHashSet()
    return PlaylistSyncPlan(
        remove = current.map { it.videoId }.filterNot { it in wantedSet },
        insert = insert,
        move = move,
    )
}
