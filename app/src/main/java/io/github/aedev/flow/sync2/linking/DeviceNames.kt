package io.github.aedev.flow.sync2.linking

import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync2.crypto.ProtocolException
import io.github.aedev.flow.sync2.engine.LinkedException
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.roster.RosterState
import io.github.aedev.flow.sync2.roster.applyEntry
import io.github.aedev.flow.sync2.roster.openDeviceMeta

/**
 * Device names and platforms, as the latest roster entry naming each device says. Names are only
 * shown, so one that can't be read is left out instead of stopping sync.
 */
suspend fun deviceNames(session: Session): Map<String, DeviceMeta> {
    val names = HashMap<String, DeviceMeta>()
    var state: RosterState? = null
    for (entry in session.entries) {
        val after = applyEntry(state, entry)
        for ((did, sealed) in entry.meta.orEmpty()) {
            openName(session, after.epoch, entry.seq, did, sealed)?.let { names[did] = it }
        }
        state = after
    }
    return names
}

private suspend fun openName(
    session: Session,
    epoch: Int,
    seq: Long,
    did: String,
    sealed: String,
): DeviceMeta? =
    try {
        val content =
            openDeviceMeta(
                session.epochKey(epoch),
                session.group,
                epoch,
                seq,
                SyncBytes.fromHex(did),
                SyncBytes.b64urlDecode(sealed),
            )
        pairingJson.decodeFromString(DeviceMeta.serializer(), content)
    } catch (_: ProtocolException) {
        null
    } catch (_: LinkedException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
