package io.github.aedev.flow.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.R
import io.github.aedev.flow.data.profile.ProfileRepository
import io.github.aedev.flow.data.profile.SyncProfile
import io.github.aedev.flow.data.recommendation.NeuroKeySpec
import io.github.aedev.flow.sync.apply.PeerInfo
import io.github.aedev.flow.sync.apply.ReceivedCollection
import io.github.aedev.flow.sync.apply.SyncApplier
import io.github.aedev.flow.sync.crypto.DirectionalKeys
import io.github.aedev.flow.sync.crypto.SyncCrypto
import io.github.aedev.flow.sync.identity.DeviceIdentity
import io.github.aedev.flow.sync.protocol.ApplyStats
import io.github.aedev.flow.sync.protocol.Capabilities
import io.github.aedev.flow.sync.protocol.Capability
import io.github.aedev.flow.sync.protocol.CollectionWire
import io.github.aedev.flow.sync.protocol.Hello
import io.github.aedev.flow.sync.protocol.ProtocolCallbacks
import io.github.aedev.flow.sync.protocol.Selection
import io.github.aedev.flow.sync.protocol.SyncCollection
import io.github.aedev.flow.sync.protocol.SyncProtocol
import io.github.aedev.flow.sync.protocol.SyncRole
import io.github.aedev.flow.sync.protocol.TransferSummary
import io.github.aedev.flow.sync.qr.QrCodec
import io.github.aedev.flow.sync.transport.LanAddress
import io.github.aedev.flow.sync.transport.SyncConnection
import io.github.aedev.flow.sync.transport.SyncForegroundService
import io.github.aedev.flow.sync.transport.WsClient
import io.github.aedev.flow.sync.transport.WsServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val MANUAL_SHARE_TTL_SECONDS = 600L

/**
 * Owns one FLOW-SYNC/1 session end-to-end and exposes it as a single [StateFlow].
 *
 * Role (SENDER/RECEIVER) and transport (host=show-QR / client=scan-QR) are **independent**: any of
 * the four combinations is valid. This is what lets a camera-less peer (e.g. desktop) *receive* by
 * showing a QR that the sender scans. The QR carries the displayer's role ([QrCodec.ROLE_SENDER] /
 * [QrCodec.ROLE_RECEIVER]); a scanner takes the complement and rejects a non-complementary QR.
 *
 * Implements [ProtocolCallbacks] so the UI resolves the SAS/consent gates by completing the pending
 * deferreds via [confirmSas]/[confirmConsent].
 */
@Singleton
class SyncManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val okHttpClient: OkHttpClient,
        private val deviceIdentity: DeviceIdentity,
        private val applier: SyncApplier,
        private val profiles: ProfileRepository,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
        val state: StateFlow<SyncState> = _state.asStateFlow()

        private var job: Job? = null
        private var server: WsServer? = null
        private var connection: SyncConnection? = null
        private var masterKey: ByteArray? = null
        private var directionalKeys: DirectionalKeys? = null
        private var sasDeferred: CompletableDeferred<Boolean>? = null
        private var consentDeferred: CompletableDeferred<Boolean>? = null
        private var hostPairing: HostPairing? = null
        private var foregroundToken: Long? = null

        /** When the shown code lapses; the listener closes then, since scanners no longer check `exp`. */
        @Volatile private var hostExpiresAtEpochSeconds = 0L

        private class HostPairing(
            val sessionId: ByteArray,
            val masterKey: ByteArray,
            val ip: String,
            val port: Int,
            val deviceName: String,
            val role: String,
            val sending: Boolean,
        )

        // --- public API (called by the ViewModel) ---

        /** Become the host (show a QR) in the given [role]. [collections] is only used when SENDER. */
        fun host(
            role: SyncRole,
            collections: List<String>,
        ) {
            if (job?.isActive == true) return
            val previous = job
            job =
                scope.launch {
                    previous?.cancelAndJoin()
                    if (!refusedOutsideSyncProfile()) runHost(role, collections)
                }
        }

        /** Become the client (scan a QR) in the given [role]. [collections] is only used when SENDER. */
        fun join(
            role: SyncRole,
            qrText: String,
            collections: List<String>,
        ) {
            if (job?.isActive == true) return
            val previous = job
            job =
                scope.launch {
                    previous?.cancelAndJoin()
                    if (!refusedOutsideSyncProfile()) runClient(role, qrText, collections)
                }
        }

        /** A session is pairing, transferring or merging. */
        val isBusy: Boolean get() = job?.isActive == true

        // The screen already says so; this keeps any other caller from syncing another profile's data.
        private suspend fun refusedOutsideSyncProfile(): Boolean {
            if (SyncProfile.syncsHere(context)) return false
            val name = SyncProfile.name(profiles.state.first(), context)
            _state.value = SyncState.Failed(context.getString(R.string.sync_default_only_title, name))
            return true
        }

        fun extendPairingForManualShare(): String? {
            val pairing = hostPairing ?: return null
            if (_state.value !is SyncState.ShowingQr) return null
            val exp = System.currentTimeMillis() / 1000 + MANUAL_SHARE_TTL_SECONDS
            hostExpiresAtEpochSeconds = exp
            val extended = showingQr(pairing, exp, MANUAL_SHARE_TTL_SECONDS)
            _state.value = extended
            return extended.qrText
        }

        fun confirmSas(matches: Boolean) {
            sasDeferred?.complete(matches)
        }

        fun confirmConsent(accepted: Boolean) {
            consentDeferred?.complete(accepted)
        }

        fun cancel() {
            job?.cancel()
            cleanup()
            _state.value = SyncState.Idle
        }

        fun reset() {
            if (job?.isActive != true) _state.value = SyncState.Idle
        }

        // --- host (show QR); role may be SENDER or RECEIVER ---

        private fun showingQr(
            pairing: HostPairing,
            expEpochSeconds: Long,
            ttlSeconds: Long,
        ): SyncState.ShowingQr =
            SyncState.ShowingQr(
                qrText =
                    QrCodec.build(
                        sessionId = pairing.sessionId,
                        masterKey = pairing.masterKey,
                        ip = pairing.ip,
                        port = pairing.port,
                        deviceName = pairing.deviceName,
                        expEpochSeconds = expEpochSeconds,
                        role = pairing.role,
                    ),
                expiresAtEpochSeconds = expEpochSeconds,
                ttlSeconds = ttlSeconds,
                sending = pairing.sending,
                address = "${pairing.ip}:${pairing.port}",
            )

        private suspend fun runHost(
            role: SyncRole,
            collections: List<String>,
        ) {
            try {
                _state.value = SyncState.Preparing
                val sessionId = SyncCrypto.randomSessionId()
                val master = SyncCrypto.randomMasterKey()
                masterKey = master
                val keys = SyncCrypto.deriveKeys(master, sessionId)
                directionalKeys = keys
                val sas = SyncCrypto.sas(master, sessionId)

                val ip = LanAddress.resolve()
                LanAddress.logCandidates(ip)
                if (ip == null) throw IllegalStateException(context.getString(R.string.sync_error_no_network))

                foregroundToken = SyncForegroundService.start(context)
                val srv = WsServer()
                server = srv
                val port = srv.start()
                val exp = System.currentTimeMillis() / 1000 + QrCodec.DEFAULT_TTL_SECONDS
                val pairing =
                    HostPairing(
                        sessionId = sessionId,
                        masterKey = master,
                        ip = ip,
                        port = port,
                        deviceName = deviceIdentity.deviceName(),
                        role = if (role == SyncRole.SENDER) QrCodec.ROLE_SENDER else QrCodec.ROLE_RECEIVER,
                        sending = role == SyncRole.SENDER,
                    )
                hostPairing = pairing
                hostExpiresAtEpochSeconds = exp
                _state.value = showingQr(pairing, exp, QrCodec.DEFAULT_TTL_SECONDS)

                val conn = awaitPeer(srv)
                connection = conn
                _state.value = SyncState.Connecting
                val result = runProtocol(conn, isHost = true, role = role, keys, sessionId, sas, collections)
                _state.value = SyncState.Done(result.peerName, result.stats)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failWith(e)
            } finally {
                cleanup()
            }
        }

        /** Wait for the scanner until the shown code lapses (a manual share can extend it meanwhile). */
        private suspend fun awaitPeer(srv: WsServer): SyncConnection {
            while (true) {
                val remainingMs = hostExpiresAtEpochSeconds * 1000 - System.currentTimeMillis()
                if (remainingMs <= 0) throw IllegalStateException(context.getString(R.string.sync_error_no_peer))
                withTimeoutOrNull(remainingMs) { srv.awaitConnection() }?.let { return it }
            }
        }

        // --- client (scan QR); role may be SENDER or RECEIVER ---

        private suspend fun runClient(
            role: SyncRole,
            qrText: String,
            collections: List<String>,
        ) {
            try {
                _state.value = SyncState.Preparing
                val parsed =
                    when (val r = QrCodec.parse(qrText)) {
                        is QrCodec.Result.Ok -> r.qr
                        is QrCodec.Result.Err -> throw IllegalArgumentException(qrErrorMessage(r.error))
                    }
                // The QR displayer's role must be the complement of ours, or both sides would send
                // (or both receive) and the session could never make progress.
                val weAreSender = role == SyncRole.SENDER
                if (parsed.displayerIsSender == weAreSender) {
                    throw IllegalArgumentException(
                        context.getString(
                            if (weAreSender) R.string.sync_error_peer_also_send else R.string.sync_error_peer_also_receive,
                        ),
                    )
                }
                val master = parsed.masterKey
                masterKey = master
                val keys = SyncCrypto.deriveKeys(master, parsed.sessionId)
                directionalKeys = keys
                val sas = SyncCrypto.sas(master, parsed.sessionId)

                foregroundToken = SyncForegroundService.start(context)
                _state.value = SyncState.Connecting
                val conn = connectAny(parsed.wsUrls)
                connection = conn
                val result = runProtocol(conn, isHost = false, role = role, keys, parsed.sessionId, sas, collections)
                _state.value = SyncState.Done(result.peerName, result.stats)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failWith(e)
            } finally {
                cleanup()
            }
        }

        /** Dial each address the code offers, best first, until one answers. */
        private suspend fun connectAny(urls: List<String>): SyncConnection {
            var lastError: Throwable = IOException("no address to dial")
            for (url in urls) {
                try {
                    return WsClient(okHttpClient).connect(url)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    lastError = e
                }
            }
            throw lastError
        }

        private suspend fun runProtocol(
            conn: SyncConnection,
            isHost: Boolean,
            role: SyncRole,
            keys: DirectionalKeys,
            sessionId: ByteArray,
            sas: String,
            sendCollections: List<String>,
        ) = run {
            val node = deviceIdentity.hlcNode()
            val hlc = deviceIdentity.newClock().now().encode()
            val hello =
                Hello(
                    deviceId = deviceIdentity.deviceId(),
                    deviceName = deviceIdentity.deviceName(),
                    platform = deviceIdentity.platform,
                    appVersion = deviceIdentity.appVersion,
                )
            val selection =
                if (role == SyncRole.SENDER) {
                    Selection(send = sendCollections, accept = emptyList())
                } else {
                    Selection(send = emptyList(), accept = SyncCollection.ANDROID_SYNCABLE)
                }
            val protocol =
                SyncProtocol(
                    conn = conn,
                    isHost = isHost,
                    keys = keys,
                    sessionId = sessionId,
                    sasDigits = sas,
                    localHello = hello,
                    localCaps = androidCapabilities(applier.neuroKeyLanguage()),
                    localSelection = selection,
                    callbacks = callbacks,
                    buildPayload = { cols: List<String> -> applier.exportPayload(cols, node, hlc) },
                    applyReceived = { peer: PeerInfo, payload: Map<String, ReceivedCollection> ->
                        applier.applyPayload(peer, payload, hlc)
                    },
                )
            protocol.run(role)
        }

        private val callbacks =
            object : ProtocolCallbacks {
                override suspend fun confirmSas(sas: String): Boolean {
                    val deferred = CompletableDeferred<Boolean>()
                    sasDeferred = deferred
                    _state.value = SyncState.AwaitingSas(sas)
                    return deferred.await()
                }

                override suspend fun confirmConsent(summary: TransferSummary): Boolean {
                    val deferred = CompletableDeferred<Boolean>()
                    consentDeferred = deferred
                    _state.value = SyncState.AwaitingConsent(summary)
                    return deferred.await()
                }

                override fun onProgress(
                    collection: String,
                    done: Int,
                    total: Int,
                ) {
                    _state.value = SyncState.Transferring(collection, done, total)
                }
            }

        private fun androidCapabilities(keyLanguage: String) =
            Capabilities(
                mapOf(
                    SyncCollection.WATCH_HISTORY to Capability(1, produce = true, consume = true),
                    SyncCollection.PLAYLISTS to Capability(1, produce = true, consume = true),
                    SyncCollection.LIKES to Capability(1, produce = true, consume = true),
                    SyncCollection.SETTINGS to Capability(1, produce = true, consume = true),
                    SyncCollection.FLOW_NEURO_BRAIN to
                        Capability(13, produce = true, consume = true, keySpec = NeuroKeySpec.VERSION, keyLanguage = keyLanguage),
                    SyncCollection.SUBSCRIBED_CHANNELS to Capability(1, produce = true, consume = true),
                    SyncCollection.SUBSCRIPTIONS to Capability(1, produce = true, consume = true),
                    SyncCollection.MUSIC_BRAIN to Capability(1, produce = true, consume = true),
                    SyncCollection.NOTES to Capability(1, produce = true, consume = true),
                ),
            )

        private fun failWith(e: Throwable) {
            _state.value = SyncState.Failed(SyncErrorMessages.describe(context, e))
        }

        private fun cleanup() {
            runCatching { connection?.close() }
            connection = null
            runCatching { server?.stop() }
            server = null
            directionalKeys?.zeroize()
            directionalKeys = null
            masterKey?.fill(0)
            masterKey = null
            sasDeferred = null
            consentDeferred = null
            hostPairing = null
            foregroundToken?.let { token -> runCatching { SyncForegroundService.stop(context, token) } }
            foregroundToken = null
        }

        private fun qrErrorMessage(error: QrCodec.QrError): String =
            context.getString(
                when (error) {
                    QrCodec.QrError.EXPIRED -> {
                        R.string.sync_error_qr_expired
                    }

                    QrCodec.QrError.WRONG_VERSION -> {
                        R.string.sync_error_qr_wrong_version
                    }

                    QrCodec.QrError.MALFORMED, QrCodec.QrError.BAD_KEY, QrCodec.QrError.BAD_SESSION -> {
                        R.string.sync_error_qr_not_flow
                    }

                    QrCodec.QrError.BAD_ADDRESS -> {
                        R.string.sync_error_qr_bad_address
                    }

                    QrCodec.QrError.NOT_LOCAL -> {
                        R.string.sync_error_qr_not_local
                    }
                },
            )
    }
