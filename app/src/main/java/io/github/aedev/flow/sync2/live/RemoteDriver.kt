package io.github.aedev.flow.sync2.live

import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.di.ApplicationScope
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.player.Followed
import io.github.aedev.flow.player.PlaybackTarget
import io.github.aedev.flow.player.RemotePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives the device the user chose in "Play on" from this one, Spotify Connect style: every playback
 * action in the app goes there, and the music player follows what it plays.
 */
@Singleton
class RemoteDriver
    @Inject
    constructor(
        private val choice: RemoteChoice,
        private val handoff: Handoff,
        private val devices: LiveDevices,
        private val details: TrackDetails,
        @ApplicationScope private val scope: CoroutineScope,
    ) : RemotePlayer {
        private fun now(): LiveMessage.Now? = choice.did.value?.let { devices.devices.value[it]?.now }

        override val playing: Boolean get() = now()?.state == LiveMessage.PLAYING

        override val queue: List<String> get() = now()?.let(::queueOf).orEmpty()

        override val index: Int get() = now()?.let(::indexOf) ?: -1

        override fun command(
            cmd: String,
            pos: Long?,
        ) {
            val did = choice.did.value ?: return
            scope.launch { handoff.control(did, cmd, pos) }
        }

        override fun play(
            queue: List<String>,
            index: Int,
            music: Boolean,
        ) {
            val did = choice.did.value ?: return
            scope.launch { handoff.play(did, queue, index, music) }
        }

        override fun setQueue(ids: List<String>) {
            val did = choice.did.value ?: return
            if (ids == queue) return
            scope.launch { handoff.control(did, "queue", queue = ids) }
        }

        override fun release() = choice.clear()

        /** Sends the app's playback actions to the chosen device while there is one. */
        fun install() {
            scope.launch {
                choice.did.collect {
                    // Another target (a TV the music is cast to) is left alone unless a device is chosen here.
                    val other = PlaybackTarget.remote?.takeIf { remote -> remote !== this@RemoteDriver }
                    if (it != null) {
                        other?.release()
                        PlaybackTarget.remote = this@RemoteDriver
                    } else if (other == null) {
                        PlaybackTarget.remote = null
                        PlaybackTarget.followed.value = null
                    }
                }
            }
        }

        /** Keeps the choice current and the music player on the chosen device's music, while live runs. */
        suspend fun run() =
            coroutineScope {
                launch { choice.watch(merge(devices.devices, devices.online)) { devices.isOnline(it) } }
                combine(choice.did, devices.devices) { did, peers -> did?.let { peers[it] } }
                    .distinctUntilChanged { old, new -> old?.now == new?.now }
                    .collectLatest { peer ->
                        when {
                            peer?.now?.music == true -> {
                                follow(peer)
                            }

                            PlaybackTarget.remote == null || PlaybackTarget.remote === this@RemoteDriver -> {
                                PlaybackTarget.followed.value =
                                    null
                            }
                        }
                    }
            }

        private suspend fun follow(peer: LivePeer) {
            val now = peer.now ?: return
            val ids = queueOf(now)
            val at = System.currentTimeMillis()
            EnhancedMusicPlayerManager.follow(tracks(ids, now), indexOf(now), peer.position(at), now.dur)
            PlaybackTarget.followed.value = Followed(now.state == LiveMessage.PLAYING, peer.position(at), at)
            if (ids.any { details.knownSong(it) == null }) {
                details.songs(ids)
                EnhancedMusicPlayerManager.follow(tracks(ids, now), indexOf(now), peer.position(at), now.dur)
            }
        }

        private fun tracks(
            ids: List<String>,
            now: LiveMessage.Now,
        ): List<MusicTrack> = ids.map { id -> details.knownSong(id) ?: songOf(id, now) }
    }

private fun queueOf(now: LiveMessage.Now): List<String> = now.queue.ifEmpty { listOfNotNull(now.video) }

private fun indexOf(now: LiveMessage.Now): Int =
    now.index.takeIf { queueOf(now).getOrNull(it) == now.video } ?: queueOf(now).indexOf(now.video)
