package io.github.aedev.flow.player

import kotlinx.coroutines.flow.MutableStateFlow

/** Marks the app's own controller of the music session, so the session tells its requests from others'. */
const val APP_CONTROLLER_HINT = "io.github.aedev.flow.APP_CONTROLLER"

/** Another device the app's playback goes to while the user drives it from here ("Play on"). */
interface RemotePlayer {
    val playing: Boolean

    /** The queue that plays there, and where it is in it. */
    val queue: List<String>
    val index: Int

    fun command(
        cmd: String,
        pos: Long? = null,
    )

    fun play(
        queue: List<String>,
        index: Int,
        music: Boolean,
    )

    fun setQueue(ids: List<String>)

    /** Playback starts here with something the other device can't play, so this device stops driving it. */
    fun release()
}

fun RemotePlayer.playNext(id: String) = setQueue(queue.toMutableList().apply { add((index + 1).coerceIn(0, size), id) })

fun RemotePlayer.addToQueue(id: String) = setQueue(queue + id)

/** The driven device's music as the music session here shows it: whether it plays, and where it was [at]. */
data class Followed(
    val playing: Boolean,
    val position: Long,
    val at: Long,
) {
    fun positionAt(now: Long): Long = if (playing) position + (now - at) else position
}

/**
 * Where the app's playback actions go: this device's players, or [remote] while the user drives
 * another device. A request another device makes of this one runs [here], so it never bounces back.
 */
object PlaybackTarget {
    @Volatile var remote: RemotePlayer? = null

    /** Set while the music player shows the driven device's music instead of its own. */
    val followed = MutableStateFlow<Followed?>(null)

    @Volatile private var applyingHere = 0

    /** The device that takes a playback action now, or null for this one. Main thread only. */
    val current: RemotePlayer? get() = remote.takeIf { applyingHere == 0 }

    fun <T> here(block: () -> T): T {
        applyingHere++
        try {
            return block()
        } finally {
            applyingHere--
        }
    }
}
