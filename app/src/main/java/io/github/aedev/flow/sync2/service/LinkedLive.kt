package io.github.aedev.flow.sync2.service

import android.util.Log
import io.github.aedev.flow.di.ApplicationScope
import io.github.aedev.flow.sync2.data.GroupRecord
import io.github.aedev.flow.sync2.data.LinkedDatabase
import io.github.aedev.flow.sync2.data.serverUrl
import io.github.aedev.flow.sync2.device.DeviceKeys
import io.github.aedev.flow.sync2.live.LiveChannel
import io.github.aedev.flow.sync2.live.LiveDevices
import io.github.aedev.flow.sync2.live.LiveTransport
import io.github.aedev.flow.sync2.store.ServerStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Waits this long after a `changed` frame, so a burst of stored objects leads to one sync. */
private const val DEBOUNCE_MS = 3_000L

/** Live envelopes held while the device works through earlier ones; the server allows 20 a second per sender. */
private const val ENVELOPE_BUFFER = 64
private const val FIRST_RETRY_MS = 5_000L
private const val LAST_RETRY_MS = 5 * 60_000L

/**
 * Keeps the server's live socket open while the app is in the foreground and syncs soon after
 * another device stores something (spec/live.md, Server relay), so changes arrive without polling.
 */
@Singleton
class LinkedLive
    @Inject
    constructor(
        private val db: LinkedDatabase,
        private val keys: DeviceKeys,
        private val sync: LinkedSync,
        private val live: LiveChannel,
        private val devices: LiveDevices,
        @ApplicationScope private val scope: CoroutineScope,
    ) {
        private var running: Job? = null

        fun start() {
            if (running?.isActive == true) return
            running =
                scope.launch {
                    db
                        .groups()
                        .observeGroup()
                        .distinctUntilChangedBy { it?.let { record -> Triple(record.groupId, record.storeUrl, record.instance) } }
                        .collectLatest { record -> if (record?.serverUrl != null) stayConnected(record) }
                }
        }

        fun stop() {
            running?.cancel()
            running = null
        }

        private suspend fun stayConnected(record: GroupRecord) {
            var retry = FIRST_RETRY_MS
            while (true) {
                val outcome =
                    try {
                        connect(sync.server(record, keys.load()) ?: return)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Live channel unavailable: ${e.linkedError()}")
                        Outcome.FAILED
                    }
                // A server without the live channel answers 404; the app tries again next time it opens.
                if (outcome == Outcome.UNSUPPORTED) return
                retry = if (outcome == Outcome.OPENED) FIRST_RETRY_MS else (retry * 2).coerceAtMost(LAST_RETRY_MS)
                delay(retry)
            }
        }

        private enum class Outcome { OPENED, FAILED, UNSUPPORTED }

        /** One connection, until it closes. */
        private suspend fun connect(server: ServerStore): Outcome =
            coroutineScope {
                val openedAt = System.currentTimeMillis()
                // True: another device stored something. False: the socket opened.
                val changed = Channel<Boolean>(Channel.CONFLATED)
                val closed = CompletableDeferred<Outcome>()
                val opened = AtomicBoolean(false)
                val ready = CompletableDeferred<Unit>()
                // Live envelopes from the other devices (spec/live.md, Server relay).
                val envelopes = Channel<ByteArray>(ENVELOPE_BUFFER)
                val socket =
                    server.live(
                        object : WebSocketListener() {
                            override fun onOpen(
                                webSocket: WebSocket,
                                response: Response,
                            ) {
                                opened.set(true)
                                changed.trySend(false)
                                ready.complete(Unit)
                            }

                            override fun onMessage(
                                webSocket: WebSocket,
                                bytes: ByteString,
                            ) {
                                envelopes.trySend(bytes.toByteArray())
                            }

                            override fun onMessage(
                                webSocket: WebSocket,
                                text: String,
                            ) {
                                val frame = serverFrame(text)
                                when (frame?.type) {
                                    "changed" -> changed.trySend(true)
                                    "peers" -> devices.serverPeers(frame.online.toSet())
                                }
                            }

                            override fun onClosing(
                                webSocket: WebSocket,
                                code: Int,
                                reason: String,
                            ) {
                                webSocket.close(code, null)
                            }

                            override fun onClosed(
                                webSocket: WebSocket,
                                code: Int,
                                reason: String,
                            ) {
                                closed.complete(if (opened.get()) Outcome.OPENED else Outcome.FAILED)
                            }

                            override fun onFailure(
                                webSocket: WebSocket,
                                t: Throwable,
                                response: Response?,
                            ) {
                                closed.complete(
                                    when {
                                        response?.code == 404 -> Outcome.UNSUPPORTED
                                        opened.get() -> Outcome.OPENED
                                        else -> Outcome.FAILED
                                    },
                                )
                            }
                        },
                    )
                val syncing =
                    launch {
                        for (stored in changed) {
                            delay(DEBOUNCE_MS)
                            val again = changed.tryReceive().getOrNull() == true
                            // Changes stored while the socket was closed sent no frame; the sync on app open may have fetched them already.
                            if (stored || again || (sync.lastSync.value?.atMs ?: 0) < openedAt) sync.syncNow()
                        }
                    }
                val relaying =
                    launch {
                        ready.await()
                        val detach =
                            live.attach(
                                object : LiveTransport {
                                    override suspend fun send(envelope: ByteArray) {
                                        socket.send(envelope.toByteString())
                                    }
                                },
                            )
                        try {
                            for (envelope in envelopes) live.receive(envelope)
                        } finally {
                            detach()
                        }
                    }
                try {
                    closed.await()
                } finally {
                    syncing.cancel()
                    relaying.cancel()
                    devices.serverPeers(emptySet())
                    socket.close(NORMAL_CLOSURE, null)
                }
            }

        /** A text frame from the server: `changed`, or `peers` with who is connected; null for anything else. */
        private fun serverFrame(text: String): ServerFrame? =
            try {
                frames.decodeFromString(ServerFrame.serializer(), text)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }

        @Serializable
        private class ServerFrame(
            val type: String,
            val online: List<String> = emptyList(),
        )

        private companion object {
            val frames = Json { ignoreUnknownKeys = true }
            const val TAG = "LinkedLive"
            const val NORMAL_CLOSURE = 1000
        }
    }
