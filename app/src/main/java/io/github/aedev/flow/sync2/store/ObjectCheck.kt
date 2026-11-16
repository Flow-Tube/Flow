package io.github.aedev.flow.sync2.store

import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync2.crypto.ProtocolException
import io.github.aedev.flow.sync2.crypto.sha256
import io.github.aedev.flow.sync2.roster.Entry
import io.github.aedev.flow.sync2.roster.replayRoster

/** Whether [bytes] are the blob [path] names. */
internal fun namesBlob(
    path: String,
    bytes: ByteArray,
): Boolean = path.endsWith("/${SyncBytes.toHex(sha256(bytes))}.b")

/**
 * What this device [me] keeps of what a store or a peer hands it. A held object is never replaced, so bytes that
 * aren't what their path names stay out: the next read asks for them again. [chain] names who signs what.
 */
internal class ObjectCheck(
    private val me: String,
    chain: List<Entry>,
) {
    private val group = chain.firstOrNull()?.let { SyncBytes.fromHex(it.group) }
    private val senders =
        chain
            .takeIf { it.isNotEmpty() }
            ?.let(::replayRoster)
            ?.senders
            .orEmpty()

    fun admits(
        path: String,
        bytes: ByteArray,
    ): Boolean {
        if (isOwn(path)) return false
        if (StorePaths.isBlob(path)) return namesBlob(path, bytes)
        val (did, seq) = StorePaths.parseLog(path) ?: return false
        val sender = senders[did] ?: return false
        return try {
            val parsed = parseObject(bytes, checkNotNull(group))
            parsed.verify(sender.sig)
            SyncBytes.toHex(parsed.header.sender) == did && parsed.header.seq == seq
        } catch (_: ProtocolException) {
            false
        }
    }

    /** This device's own objects come only from itself. */
    fun isOwn(path: String): Boolean = path.startsWith("dev/$me/")
}
