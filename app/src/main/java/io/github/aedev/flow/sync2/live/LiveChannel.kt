package io.github.aedev.flow.sync2.live

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.platform.DeviceFormFactor
import io.github.aedev.flow.platform.DeviceFormFactorDetector
import io.github.aedev.flow.sync2.data.GroupRecord
import io.github.aedev.flow.sync2.data.LinkedDatabase
import io.github.aedev.flow.sync2.data.LiveCounterRow
import io.github.aedev.flow.sync2.data.RosterRow
import io.github.aedev.flow.sync2.device.DeviceKeys
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.linking.deviceNames
import io.github.aedev.flow.sync2.store.GroupStore
import io.github.aedev.flow.sync2.store.LocalStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Something a live envelope goes out on: the server's live socket, or a LAN session. */
interface LiveTransport {
    suspend fun send(envelope: ByteArray)
}

/**
 * The group's live messages (spec/live.md): seals what this device sends with a counter that never
 * repeats, sends it on every open transport, and hands on what other devices send, refusing replays.
 */
@Singleton
class LiveChannel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val db: LinkedDatabase,
        private val keys: DeviceKeys,
        private val local: LocalStore,
    ) {
        private val lock = Mutex()
        private val transports = MutableStateFlow<Set<LiveTransport>>(emptySet())
        private val arrivals = MutableSharedFlow<LiveReceived>(extraBufferCapacity = 64)
        private val sealed = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
        private var cached: Pair<Pair<GroupRecord, List<RosterRow>>, Session>? = null

        /** Whether any device can be reached now, through the server or the LAN. */
        val connected: StateFlow<Set<LiveTransport>> = transports.asStateFlow()

        /** What other current devices send, in the order it arrives. */
        val messages: SharedFlow<LiveReceived> = arrivals.asSharedFlow()

        /** Every envelope this device sends, for LAN peers it has no session with right now. */
        val envelopes: SharedFlow<ByteArray> = sealed.asSharedFlow()

        /** Starts sending on [transport] and tells the devices there that this one is online; returns how to stop. */
        suspend fun attach(transport: LiveTransport): () -> Unit {
            transports.update { it + transport }
            send(presence())
            return { transports.update { it - transport } }
        }

        /** Sends [message] to every reachable device; returns its counter, or null when this device isn't linked or a restore is unfinished. */
        suspend fun send(message: LiveMessage): Long? =
            lock.withLock {
                val session = session()?.takeUnless { it.removalPending } ?: return null
                val seq = (db.live().counter(session.me) ?: 0) + 1
                // Saved before it leaves, so a crash can't make a counter repeat.
                db.live().setCounter(LiveCounterRow(session.me, seq))
                val envelope = session.sealLive(message, seq)
                transports.value.forEach { it.send(envelope) }
                sealed.emit(envelope)
                seq
            }

        /** Takes an envelope a transport received; anything that isn't a new message from a current device is dropped. */
        suspend fun receive(envelope: ByteArray) {
            lock.withLock {
                val received = session()?.openLive(envelope) ?: return
                if (received.seq <= (db.live().counter(received.sender) ?: 0)) return
                db.live().setCounter(LiveCounterRow(received.sender, received.seq))
                arrivals.emit(received)
            }
        }

        /** The names the group's devices go by, from what this device holds, without asking the store. */
        suspend fun names(): Map<String, String> =
            lock.withLock { session()?.let { deviceNames(it) } }?.mapValues { it.value.name }.orEmpty()

        /** This device's id in the group, or null when it isn't linked. */
        suspend fun me(): String? = lock.withLock { session()?.me }

        private fun presence(): LiveMessage.Presence = LiveMessage.Presence(listOf("play", "remote"), deviceKind(context))

        /** A session on what this device holds, kept until the group's record or roster changes. */
        private suspend fun session(): Session? {
            val current = (db.groups().group() ?: return null) to db.groups().roster()
            cached?.takeIf { it.first == current }?.let { return it.second }
            return try {
                Session.open(db, GroupStore(local, null), keys.load()).also { cached = current to it }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }

/** The icon other devices show for this one: `tv`, `tablet` or `phone`. */
fun deviceKind(context: Context): String =
    when {
        DeviceFormFactorDetector.detect(context) == DeviceFormFactor.TV -> "tv"
        context.resources.configuration.smallestScreenWidthDp >= 600 -> "tablet"
        else -> "phone"
    }
