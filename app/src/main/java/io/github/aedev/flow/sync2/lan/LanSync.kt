package io.github.aedev.flow.sync2.lan

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.di.ApplicationScope
import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.crypto.SyncCrypto
import io.github.aedev.flow.sync.qr.LocalNetworkAddress
import io.github.aedev.flow.sync.transport.SyncConnection
import io.github.aedev.flow.sync.transport.WsClient
import io.github.aedev.flow.sync.transport.WsServer
import io.github.aedev.flow.sync2.crypto.Purpose
import io.github.aedev.flow.sync2.crypto.lanTag
import io.github.aedev.flow.sync2.crypto.purposeKey
import io.github.aedev.flow.sync2.data.LanAddressRow
import io.github.aedev.flow.sync2.data.LinkedDatabase
import io.github.aedev.flow.sync2.data.observeEpoch
import io.github.aedev.flow.sync2.data.toEntries
import io.github.aedev.flow.sync2.device.DeviceKeys
import io.github.aedev.flow.sync2.engine.LinkedException
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.live.LiveChannel
import io.github.aedev.flow.sync2.live.LiveDevices
import io.github.aedev.flow.sync2.live.LiveTransport
import io.github.aedev.flow.sync2.roster.replayRoster
import io.github.aedev.flow.sync2.service.LinkedSync
import io.github.aedev.flow.sync2.service.linkedError
import io.github.aedev.flow.sync2.store.GroupStore
import io.github.aedev.flow.sync2.store.LocalStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

const val SESSION_PATH = "/flow-sync/2"

/** An address isn't dialed again sooner than this, unless it answered last time and a live message waits. */
private const val REDIAL_MS = 60_000L

/** A side that has heard nothing for this long takes the other's pull as done. */
private const val QUIET_MS = 3_000L

private const val CONNECT_TIMEOUT_MS = 3_000L
private const val ATTEMPT_MS = 30_000L

/** A device that leaves meets the others this long at most, so they pull its `leave` (spec/live.md, LAN groups). */
private const val FAREWELL_MS = 10_000L

private const val HOUR_S = 3_600L
private const val HOUR_MS = HOUR_S * 1_000
private const val FIRST_RETRY_MS = 5_000L
private const val LAST_RETRY_MS = 5 * 60_000L

/**
 * Syncs linked devices on the same network (spec/live.md, LAN transport), for LAN groups and groups
 * on a server alike: it serves sessions, advertises this device, finds the others over mDNS and at
 * the addresses of earlier sessions, and when two members meet each pulls what it lacks; the usual
 * sync then merges it.
 */
@Singleton
class LanSync
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val db: LinkedDatabase,
        private val keys: DeviceKeys,
        private val local: LocalStore,
        private val sync: LinkedSync,
        private val live: LiveChannel,
        private val devices: LiveDevices,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        private val open = ConcurrentHashMap.newKeySet<String>()
        private val seen = MutableStateFlow<Map<String, Long>>(emptyMap())
        private val dialed = ConcurrentHashMap<String, Long>()

        /** The mDNS names this device advertised, which it never dials. */
        private val names = ConcurrentHashMap.newKeySet<String>()
        private var running: Job? = null
        private var users = 0
        private var group: String? = null

        /** Devices of this group met on this network while this process runs, and when. */
        val nearby: StateFlow<Map<String, Long>> = seen.asStateFlow()

        /**
         * Serves the group this device is in, starting over when it joins another one, the epoch moves
         * on or the network changes. Runs until every [start] has had its [stop].
         */
        @Synchronized
        fun start() {
            users++
            if (running?.isActive == true) return
            Log.i(TAG, "LAN sync started")
            running =
                scope.launch {
                    try {
                        db
                            .groups()
                            .observeGroup()
                            .map { it?.groupId }
                            .distinctUntilChanged()
                            .collectLatest { id ->
                                if (id != group) forgetPeers(id)
                                if (id != null) {
                                    combine(db.groups().observeEpoch(), defaultNetwork(context)) { epoch, network -> epoch to network }
                                        .collectLatest { (epoch, network) -> if (epoch != null && network != null) keepServing() }
                                }
                            }
                    } finally {
                        devices.lanPeers { emptySet() }
                    }
                }
        }

        @Synchronized
        fun stop() {
            users = (users - 1).coerceAtLeast(0)
            if (users > 0) return
            running?.cancel()
            running = null
            Log.i(TAG, "LAN sync stopped")
        }

        /** A short look for other members, for a periodic attempt on Wi-Fi. */
        suspend fun attempt() {
            start()
            try {
                delay(ATTEMPT_MS)
            } finally {
                stop()
            }
        }

        /**
         * Brings the members this device can reach the `leave` it just appended, for up to 10 seconds,
         * before it forgets the group (spec/live.md, LAN groups). [session] is from before the leave.
         */
        suspend fun farewell(session: Session) {
            withTimeoutOrNull(FAREWELL_MS) {
                coroutineScope {
                    for (row in db.lan().addresses()) {
                        launch {
                            val connection = connect(row.address) ?: return@launch
                            try {
                                converse(connectLink(connection, session), session, leaving = true)
                                Log.i(TAG, "LAN farewell delivered")
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.w(TAG, "LAN farewell failed: ${e.linkedError()}")
                            } finally {
                                connection.close()
                            }
                        }
                    }
                }
            }
        }

        private suspend fun session(): Session? {
            db.groups().group() ?: return null
            return open()
        }

        private suspend fun open(): Session = Session.open(db, GroupStore(local, null), keys.load())

        /** Peers met in another group are no longer this device's. */
        private fun forgetPeers(id: String?) {
            group = id
            seen.value = emptyMap()
            dialed.clear()
            devices.lanPeers { emptySet() }
        }

        /** Serves afresh each hour, when the tag changes, and again after a failure, until cancelled. */
        private suspend fun keepServing() {
            var retry = FIRST_RETRY_MS
            while (true) {
                try {
                    withTimeoutOrNull(HOUR_MS - System.currentTimeMillis() % HOUR_MS) { serve() }
                    retry = FIRST_RETRY_MS
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "LAN sync stopped: ${e.linkedError()}")
                    delay(retry)
                    retry = (retry * 2).coerceAtMost(LAST_RETRY_MS)
                }
            }
        }

        private suspend fun serve(): Nothing =
            coroutineScope {
                val session = open()
                val lanKey = purposeKey(session.currentKey(), session.group, session.state.epoch, Purpose.LAN)
                val server = WsServer(SESSION_PATH) { connection -> accept(connection) }
                val discovery = LanDiscovery(context)
                val name = "Flow-" + SyncBytes.toHex(SyncCrypto.randomBytes(2))
                names += name
                try {
                    val port = server.listenOnLastPort(context)
                    val now = System.currentTimeMillis() / 1000
                    discovery.advertise(name, port, lanTag(lanKey, now))
                    val tags = listOf(now - HOUR_S, now, now + HOUR_S).map { SyncBytes.toHex(lanTag(lanKey, it)) }.toSet()
                    discovery.discover(names) { host, peerPort, tag ->
                        val at = LocalNetworkAddress.urlHost(host)
                        if (tag in tags && LocalNetworkAddress.isLocal(host) && due("$at:$peerPort")) launch { dial(at, peerPort, port) }
                    }
                    Log.i(TAG, "LAN sync serving")
                    // On networks that drop multicast, members are only found where they listened last.
                    launch { reach(port) }
                    // Something to send: a live message, or objects this device wrote.
                    launch { live.envelopes.collect { reach(port, it) } }
                    launch {
                        db
                            .groups()
                            .observeGroup()
                            .map { it?.ownSeq }
                            .distinctUntilChanged()
                            .drop(1)
                            .collect { reach(port) }
                    }
                    launch {
                        db.groups().observeRoster().collect { rows ->
                            if (rows.isNotEmpty()) db.lan().keepOnly(replayRoster(rows.toEntries()).members.keys.toList())
                        }
                    }
                    discovery.failed.await()
                    throw LinkedException.LanUnreachable()
                } finally {
                    discovery.stop()
                    server.stop()
                }
            }

        private fun due(address: String): Boolean = dialed[address]?.let { System.currentTimeMillis() - it >= REDIAL_MS } ?: true

        /**
         * Dials the members remembered from earlier sessions that have no session open now: each at
         * most once a minute, but whenever [envelope] waits for one whose address answered last.
         */
        private suspend fun CoroutineScope.reach(
            port: Int,
            envelope: ByteArray? = null,
        ) {
            val reachable = devices.online.value
            for (row in db.lan().addresses()) {
                val address = row.address
                if (address !in open && (due(address) || (envelope != null && row.did in reachable))) {
                    launch { dial(row.host, row.port, port, envelope) }
                }
            }
        }

        /** A session with the member listening at [host] and [peerPort], which first brings it [envelope] when there is one. */
        private suspend fun dial(
            host: String,
            peerPort: Int,
            port: Int,
            envelope: ByteArray? = null,
        ) {
            val address = "$host:$peerPort"
            if (!open.add(address)) return
            dialed[address] = System.currentTimeMillis()
            try {
                val link = connect(address)?.let { meet(it, port, envelope) }
                if (link != null) {
                    remember(link.peer, host, peerPort)
                } else {
                    val gone = db.lan().at(host, peerPort).toSet()
                    devices.lanPeers { it - gone }
                    Log.i(TAG, "LAN dial failed")
                }
            } finally {
                open.remove(address)
            }
        }

        private suspend fun connect(address: String): SyncConnection? =
            withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                runCatching { WsClient(OkHttpClient()).connect("ws://$address$SESSION_PATH") }.getOrNull()
            }

        /** A member that dialed this one is remembered where its hello says it listens. */
        private suspend fun accept(connection: SyncConnection) {
            val link = meet(connection, port = null) ?: return
            val host = connection.remoteHost
            val port = link.peerPort
            if (host != null && port != null) remember(link.peer, LocalNetworkAddress.urlHost(host), port)
        }

        private suspend fun remember(
            did: String,
            host: String,
            port: Int,
        ) {
            db.lan().save(LanAddressRow(did, host, port))
            devices.lanPeers { it + did }
        }

        /**
         * One session with another member, which this device dialed when it names its own [port]: both
         * pull, live messages travel while it lasts, then the usual sync merges what arrived. Returns
         * the closed link when it got that far.
         */
        private suspend fun meet(
            connection: SyncConnection,
            port: Int?,
            envelope: ByteArray? = null,
        ): LanLink? {
            try {
                val session = session() ?: return null
                val link = if (port == null) acceptLink(connection, session) else connectLink(connection, session, port)
                converse(link, session, envelope = envelope)
                seen.update { it + (link.peer to System.currentTimeMillis()) }
                Log.i(TAG, if (port == null) "LAN session accepted" else "LAN session dialed")
                sync.syncNow()
                return link
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "LAN session failed: ${e.linkedError()}")
                return null
            } finally {
                connection.close()
            }
        }

        /**
         * Answers the other member's requests until its pull is over. This device pulls too and sends
         * live messages on the session, unless it is [leaving].
         */
        private suspend fun converse(
            link: LanLink,
            session: Session,
            leaving: Boolean = false,
            envelope: ByteArray? = null,
        ) = coroutineScope {
            val peer = LanPeer(link, session, local) { live.receive(it) }
            val reader = peer.start(this)
            val detach =
                if (leaving) {
                    null
                } else {
                    live.attach(
                        object : LiveTransport {
                            override suspend fun send(envelope: ByteArray) = peer.sendLive(envelope)
                        },
                    )
                }
            try {
                envelope?.let { peer.sendLive(it) }
                if (!leaving) peer.pull()
                peer.awaitQuiet(QUIET_MS)
            } finally {
                detach?.invoke()
            }
            link.close()
            reader.join()
        }

        private companion object {
            const val TAG = "LanSync"
        }
    }

private val LanAddressRow.address: String get() = "$host:$port"
