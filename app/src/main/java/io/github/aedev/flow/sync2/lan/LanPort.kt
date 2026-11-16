package io.github.aedev.flow.sync2.lan

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import io.github.aedev.flow.sync.transport.WsServer
import io.github.aedev.flow.sync2.device.linkedDeviceDataStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.net.ServerSocket

private val LAN_PORT = intPreferencesKey("lan_port")
private const val PORT_TRIES = 10
private const val PORT_WAIT_MS = 100L

/** Listens on the port used last, when it is free, so members that remember it reach this device (spec/live.md, Discovery). */
suspend fun WsServer.listenOnLastPort(context: Context): Int {
    val store = context.linkedDeviceDataStore
    val last = store.data.first()[LAN_PORT]
    val port = start(last?.let { freed(it) } ?: 0)
    if (port != last) store.edit { it[LAN_PORT] = port }
    return port
}

/** The port this device's LAN service listens on, chosen now when there is none yet, for pairing to name. */
suspend fun lanPort(context: Context): Int {
    val store = context.linkedDeviceDataStore
    store.data.first()[LAN_PORT]?.let { return it }
    val port = ServerSocket(0).use { it.localPort }
    store.edit { it[LAN_PORT] = port }
    return port
}

/** [port] once it is free, as the previous listener may take a moment to let go of it; null if it stays taken. */
private suspend fun freed(port: Int): Int? {
    repeat(PORT_TRIES) {
        if (portFree(port)) return port
        delay(PORT_WAIT_MS)
    }
    return null
}

/** Checked before binding: the server reports a port taken only as an uncaught error. */
internal fun portFree(port: Int): Boolean = runCatching { ServerSocket(port).close() }.isSuccess
