package io.github.aedev.flow.sync2.live

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A device that hasn't answered by then didn't take the request. Longer than the 20 s a receiving device waits
 * for playback to start, so a slow start is never reported as a failure while that device goes on to play it.
 */
private const val RESULT_MS = 25_000L

/**
 * Handoff and remote control between the group's devices, like a single active player (spec/live.md,
 * Playing on another device): this device answers `play` and `cmd` addressed to it, once the user
 * allows the sender, and sends them to others.
 */
@Singleton
class Handoff
    @Inject
    constructor(
        private val live: LiveChannel,
        private val devices: LiveDevices,
        private val consent: LiveConsent,
        private val local: LocalPlayback,
        private val now: NowPlaying,
    ) {
        private val waiting = ConcurrentHashMap<Long, CompletableDeferred<LiveMessage.Result>>()
        private val applied = MutableSharedFlow<String>(extraBufferCapacity = 4)

        /** Devices whose `play` or `cmd` this device just applied, so it can say who drives it. */
        val controlledBy: SharedFlow<String> = applied.asSharedFlow()

        /** Results that came back before their request was registered, on a fast LAN. */
        private val early = ConcurrentHashMap<Long, LiveMessage.Result>()

        /** Answers what other devices send until cancelled; a question to the user never holds up the rest. */
        suspend fun run() {
            coroutineScope {
                live.messages.collect { received ->
                    devices.heard(received)
                    launch { handle(received) }
                }
            }
        }

        private suspend fun handle(received: LiveReceived) {
            val me = live.me() ?: return
            when (val message = received.message) {
                is LiveMessage.Play -> {
                    if (message.to == me) answer(received) { local.start(message, devices.devices.value[received.sender]?.now) }
                }

                is LiveMessage.Cmd -> {
                    if (message.to == me) answer(received) { local.command(message) }
                }

                is LiveMessage.Result -> {
                    if (message.to ==
                        me
                    ) {
                        waiting.remove(message.ref)?.complete(message) ?: early.put(message.ref, message)
                    }
                }

                else -> {
                    Unit
                }
            }
        }

        private suspend fun answer(
            received: LiveReceived,
            apply: suspend () -> String?,
        ) {
            val reason = if (consent.allows(received.sender)) apply() else "declined"
            live.send(LiveMessage.Result(received.sender, received.seq, reason == null, reason))
            if (reason == null) applied.emit(received.sender)
        }

        /** Moves this device's session to [to]; this device pauses only once [to] says it started, so a failed handoff never stops the music. */
        suspend fun transfer(to: String): Boolean {
            val current = now.snapshot()
            val video = current.video ?: return false
            val play =
                LiveMessage.Play(
                    to,
                    video,
                    current.pos,
                    current.music,
                    current.link,
                    current.queue,
                    current.index,
                    LiveMessage.PLAYING,
                )
            val ok = request(play)?.ok == true
            if (ok) local.release()
            return ok
        }

        /**
         * Brings the session of the device the user drives, [from], or else the current device's,
         * here: it pauses, and this device starts from its latest `now`.
         */
        suspend fun pull(from: String? = null): Boolean {
            val active = devices.controlled(from) ?: return false
            val playing = active.now ?: return false
            val video = playing.video ?: return false
            val me = live.me() ?: return false
            live.send(LiveMessage.Cmd(active.did, "pause"))
            val position = active.position(System.currentTimeMillis())
            val play = LiveMessage.Play(me, video, position, playing.music, playing.link, playing.queue, playing.index, LiveMessage.PLAYING)
            return local.start(play, playing) == null
        }

        /**
         * Moves the session of [from], the device the user drives from here, to [to]: [to] starts it
         * from [from]'s latest `now`, and only then does [from] pause.
         */
        suspend fun move(
            from: String,
            to: String,
        ): Boolean {
            val source = devices.devices.value[from] ?: return false
            val session = source.now ?: return false
            val video = session.video ?: return false
            val at = System.currentTimeMillis()
            val play =
                LiveMessage.Play(
                    to,
                    video,
                    source.position(at),
                    session.music,
                    session.link,
                    session.queue,
                    session.index,
                    session.state,
                )
            val ok = request(play)?.ok == true
            if (ok) live.send(LiveMessage.Cmd(from, "pause"))
            return ok
        }

        /** Controls playback on [to]; true once it applied the command. */
        suspend fun control(
            to: String,
            cmd: String,
            pos: Long? = null,
            vol: Int? = null,
            queue: List<String>? = null,
        ): Boolean = request(LiveMessage.Cmd(to, cmd, pos, vol, queue))?.ok == true

        /** Starts [queue] at [index] on [to], the device the user drives from here; true once it started. */
        suspend fun play(
            to: String,
            queue: List<String>,
            index: Int,
            music: Boolean,
        ): Boolean {
            val (ids, at) = queueWindow(queue, index) ?: return false
            val play = LiveMessage.Play(to, ids[at], 0, music, now.snapshot().link, ids, at, LiveMessage.PLAYING)
            return request(play)?.ok == true
        }

        private suspend fun request(message: LiveMessage): LiveMessage.Result? {
            val seq = live.send(message) ?: return null
            val answer = CompletableDeferred<LiveMessage.Result>()
            waiting[seq] = answer
            early.remove(seq)?.let { answer.complete(it) }
            return try {
                withTimeoutOrNull(RESULT_MS) { answer.await() }
            } finally {
                waiting.remove(seq)
            }
        }
    }

/** At most [LiveMessage.QUEUE_LIMIT] of [queue] around [index], with where [index] lands in it; null when [index] is outside. */
internal fun queueWindow(
    queue: List<String>,
    index: Int,
): Pair<List<String>, Int>? {
    if (index !in queue.indices) return null
    val start = (index - LiveMessage.QUEUE_LIMIT / 2).coerceIn(0, (queue.size - LiveMessage.QUEUE_LIMIT).coerceAtLeast(0))
    return queue.subList(start, (start + LiveMessage.QUEUE_LIMIT).coerceAtMost(queue.size)) to index - start
}
