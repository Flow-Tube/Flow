package io.github.aedev.flow.sync2.live

import kotlinx.coroutines.CancellationException

/**
 * Hands [envelope] to each of [transports] on its own. A LAN session whose socket is already
 * closing throws; it goes to [onFailed] and the rest still get the envelope, because a live message
 * is never worth stopping the app for (#2161).
 */
internal suspend fun deliverLive(
    transports: Collection<LiveTransport>,
    envelope: ByteArray,
    onFailed: (LiveTransport, Exception) -> Unit,
) {
    for (transport in transports) {
        try {
            transport.send(envelope)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onFailed(transport, e)
        }
    }
}
