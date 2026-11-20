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

/**
 * Starts live traffic on a session over [transport] that was opened to carry [waiting]. [attach]
 * joins the session to the live channel, or is null when this device only serves it. Returns how
 * to leave the channel again.
 */
internal suspend fun startSessionLive(
    transport: LiveTransport,
    waiting: ByteArray?,
    attach: (suspend (LiveTransport) -> () -> Unit)?,
): (() -> Unit)? {
    val detach = attach?.invoke(transport)
    try {
        waiting?.let { transport.send(it) }
    } catch (e: Throwable) {
        detach?.invoke()
        throw e
    }
    return detach
}
