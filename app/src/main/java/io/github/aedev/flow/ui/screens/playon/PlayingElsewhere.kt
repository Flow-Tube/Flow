package io.github.aedev.flow.ui.screens.playon

import io.github.aedev.flow.sync2.live.LivePeer

/**
 * A device's `now` counts as current this long after it arrived. A playing device repeats it every
 * 30 seconds, so this leaves room for one lost repeat.
 */
internal const val PLAYING_ELSEWHERE_FRESH_MS = 75_000L

/** Another device playing on its own, as the mini player slot offers it, until [expiresAt] unless it tells more. */
internal data class PlayingElsewhereHint(
    val peer: LivePeer,
    val expiresAt: Long,
)

/**
 * The device to show as playing elsewhere at [at]: none while the user drives a device ([chosen]),
 * else the one whose fresh `now` playing arrived last, leaving out each device whose track the user
 * [closed] the hint for (device id to the video it played then).
 */
internal fun findPlayingElsewhere(
    peers: Map<String, LivePeer>,
    chosen: String?,
    closed: Map<String, String?>,
    at: Long,
): PlayingElsewhereHint? {
    if (chosen != null) return null
    return peers.values
        .filter { it.playing && at < it.nowAt + PLAYING_ELSEWHERE_FRESH_MS && !isClosed(it, closed) }
        .maxByOrNull { it.nowAt }
        ?.let { PlayingElsewhereHint(it, it.nowAt + PLAYING_ELSEWHERE_FRESH_MS) }
}

/** [closed] with [video] on [did] closed, replacing any track closed on that device before. */
internal fun withClosedHint(
    closed: Map<String, String?>,
    did: String,
    video: String?,
): Map<String, String?> = closed + (did to video)

private fun isClosed(
    peer: LivePeer,
    closed: Map<String, String?>,
): Boolean = peer.did in closed && closed[peer.did] == peer.now?.video
