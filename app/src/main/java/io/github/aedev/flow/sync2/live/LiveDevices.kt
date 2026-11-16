package io.github.aedev.flow.sync2.live

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** A device heard from recently counts as online even when no transport lists it, as one that only dialed this one. */
private const val HEARD_MS = 90_000L

/** What this device knows of another one from the live channel. */
data class LivePeer(
    val did: String,
    val kind: String? = null,
    val can: List<String> = emptyList(),
    /** Its latest `now`, and when it arrived. */
    val now: LiveMessage.Now? = null,
    val nowAt: Long = 0,
    val heardAt: Long = 0,
) {
    /** Where its playback is at [at]: `pos` moved on from when the `now` arrived while it plays (spec/live.md). */
    fun position(at: Long): Long {
        val now = now ?: return 0
        if (now.state != LiveMessage.PLAYING) return now.pos
        val moved = now.pos + (at - nowAt)
        return if (now.dur > 0) moved.coerceAtMost(now.dur) else moved
    }

    val playing: Boolean get() = now?.video != null && now.state == LiveMessage.PLAYING
}

/**
 * The group's other devices as the live channel shows them: their presence and latest `now`, which
 * are online, and the active device, whose latest `now` is the most recent one playing.
 */
@Singleton
class LiveDevices
    @Inject
    constructor() {
        private val peers = MutableStateFlow<Map<String, LivePeer>>(emptyMap())
        private var server = emptySet<String>()
        private var lan = emptySet<String>()
        private val reachable = MutableStateFlow<Set<String>>(emptySet())

        val devices: StateFlow<Map<String, LivePeer>> = peers.asStateFlow()

        /**
         * The devices a transport reaches now: the server reports them connected to the live channel
         * (spec/live.md, Server relay), or their LAN address answered this device's last session.
         */
        val online: StateFlow<Set<String>> = reachable.asStateFlow()

        @Synchronized
        fun serverPeers(online: Set<String>) {
            server = online
            reachable.value = server + lan
        }

        @Synchronized
        fun lanPeers(change: (Set<String>) -> Set<String>) {
            lan = change(lan)
            reachable.value = server + lan
        }

        fun heard(
            received: LiveReceived,
            at: Long = System.currentTimeMillis(),
        ) {
            peers.update { all ->
                val peer = all[received.sender] ?: LivePeer(received.sender)
                val updated =
                    when (val message = received.message) {
                        is LiveMessage.Presence -> peer.copy(kind = message.kind, can = message.can, heardAt = at)
                        is LiveMessage.Now -> peer.copy(now = message, nowAt = at, heardAt = at)
                        else -> peer.copy(heardAt = at)
                    }
                all + (received.sender to updated)
            }
        }

        /** Whether [did] can be reached now: a transport reaches it, or it spoke lately. */
        fun isOnline(
            did: String,
            at: Long = System.currentTimeMillis(),
        ): Boolean = did in reachable.value || (peers.value[did]?.heardAt ?: 0) > at - HEARD_MS

        /** The reachable device that last told of something loaded, playing or paused: the one a remote player drives. */
        fun current(at: Long = System.currentTimeMillis()): LivePeer? =
            peers.value.values
                .filter { it.now?.video != null && isOnline(it.did, at) }
                .maxByOrNull { it.nowAt }

        /** The device the user drives, [chosen], while it can be reached with a session, else [current]. */
        fun controlled(
            chosen: String?,
            at: Long = System.currentTimeMillis(),
        ): LivePeer? = chosen?.let { peers.value[it] }?.takeIf { it.now?.video != null && isOnline(it.did, at) } ?: current(at)

        /** The other device playing whose `now` arrived last, or null when none plays. */
        fun active(): LivePeer? =
            peers.value.values
                .filter { it.playing }
                .maxByOrNull { it.nowAt }
    }
