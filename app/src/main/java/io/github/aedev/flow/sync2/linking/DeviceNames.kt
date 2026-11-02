package io.github.aedev.flow.sync2.linking

import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync2.crypto.ProtocolError
import io.github.aedev.flow.sync2.crypto.reject
import io.github.aedev.flow.sync2.engine.LinkedException
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.roster.RosterState
import io.github.aedev.flow.sync2.roster.applyEntry
import io.github.aedev.flow.sync2.roster.openDeviceMeta
import kotlinx.serialization.SerializationException

/** Device names and platforms, as the latest roster entry naming each device says. */
suspend fun deviceNames(session: Session): Map<String, DeviceMeta> {
    val names = HashMap<String, DeviceMeta>()
    var state: RosterState? = null
    for (entry in session.entries) {
        val after = applyEntry(state, entry)
        for ((did, sealed) in entry.meta.orEmpty()) {
            val content =
                openDeviceMeta(
                    session.epochKey(after.epoch),
                    session.group,
                    after.epoch,
                    entry.seq,
                    SyncBytes.fromHex(did),
                    runCatching { SyncBytes.b64urlDecode(sealed) }.getOrNull() ?: reject(ProtocolError.ROSTER_FORMAT),
                )
            names[did] =
                try {
                    pairingJson.decodeFromString(DeviceMeta.serializer(), content)
                } catch (_: SerializationException) {
                    throw LinkedException.Unexpected()
                }
        }
        state = after
    }
    return names
}
