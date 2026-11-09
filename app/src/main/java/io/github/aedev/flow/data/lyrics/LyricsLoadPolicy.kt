package io.github.aedev.flow.data.lyrics

/**
 * Which song changes may look up lyrics online. With "load lyrics only when opened" off, every one does, so the
 * player has them ready. On, a song change looks them up only for a surface that shows them (#1860).
 */
internal object LyricsLoadPolicy {
    /** The music player's song change: on demand, only while the full player is open. */
    fun playerMayFetch(
        onDemand: Boolean,
        playerOpen: Boolean,
    ): Boolean = !onDemand || playerOpen

    /** The music service's song change, which also looks up the next song: on demand, only for lock screen lyrics. */
    fun serviceMayFetch(
        onDemand: Boolean,
        lockScreenLyrics: Boolean,
    ): Boolean = !onDemand || lockScreenLyrics
}
