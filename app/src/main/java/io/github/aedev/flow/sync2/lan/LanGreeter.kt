package io.github.aedev.flow.sync2.lan

import io.github.aedev.flow.sync.transport.SyncConnection
import io.github.aedev.flow.sync2.engine.LinkedException
import io.github.aedev.flow.sync2.engine.Session
import kotlinx.coroutines.sync.Semaphore

/** Connections that haven't finished their hello. Far more than a group has members. */
internal const val GREETING_CONNECTIONS = 16

/**
 * Takes connections that dialed this device through their hello, a limited number at once. Nothing is
 * read from the roster until a hello arrives, so a connection that says nothing costs next to nothing.
 */
internal class LanGreeter(
    private val session: suspend () -> Session?,
) {
    private val slots = Semaphore(GREETING_CONNECTIONS)

    /** Whether [connection] has a slot, for [greet] to use. Without one it is closed. */
    fun takes(connection: SyncConnection): Boolean = slots.tryAcquire().also { if (!it) connection.close() }

    /** The link once the hello is done and the session it opened on, or null when this device isn't linked. */
    suspend fun greet(connection: SyncConnection): Pair<LanLink, Session>? =
        try {
            val first = connection.receiveHello() ?: throw LinkedException.Unexpected()
            session()?.let { acceptLink(connection, it, first) to it }
        } finally {
            slots.release()
        }
}
