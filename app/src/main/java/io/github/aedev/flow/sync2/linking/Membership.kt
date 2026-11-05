package io.github.aedev.flow.sync2.linking

import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.crypto.SyncCrypto
import io.github.aedev.flow.sync2.crypto.MemberSecrets
import io.github.aedev.flow.sync2.crypto.ProtocolError
import io.github.aedev.flow.sync2.crypto.reject
import io.github.aedev.flow.sync2.crypto.sealChain
import io.github.aedev.flow.sync2.crypto.wrapEpochKey
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.roster.Cut
import io.github.aedev.flow.sync2.roster.Draft
import io.github.aedev.flow.sync2.roster.Entry
import io.github.aedev.flow.sync2.roster.EpochField
import io.github.aedev.flow.sync2.roster.Op
import io.github.aedev.flow.sync2.roster.applyEntry
import io.github.aedev.flow.sync2.roster.buildEntry
import io.github.aedev.flow.sync2.roster.epochDraft
import io.github.aedev.flow.sync2.roster.sealDeviceMeta
import io.github.aedev.flow.sync2.store.Appended

/** A fresh epoch key, wrapped to every member after a change, with the previous key chained. */
class NewEpoch(
    val key: ByteArray,
    val field: EpochField,
    val wraps: Map<String, String>,
    val chain: String,
)

/** [members] are the ids (hex) and X25519 keys of everyone in the group after the change. */
fun newEpoch(
    group: ByteArray,
    n: Int,
    members: Map<String, ByteArray>,
    previousKey: ByteArray,
): NewEpoch {
    val key = SyncCrypto.randomBytes(32)
    val wraps = members.mapValues { (did, kem) -> SyncBytes.b64urlEncode(wrapEpochKey(kem, group, n, SyncBytes.fromHex(did), key)) }
    return NewEpoch(
        key,
        epochDraft(key, group, n, members.keys.toList()),
        wraps,
        SyncBytes.b64urlEncode(sealChain(key, group, n, previousKey)),
    )
}

/** Checks an entry against the roster this session verified, then appends it. */
suspend fun appendChecked(
    session: Session,
    entry: Entry,
    wraps: Map<String, String>,
    chain: String?,
) {
    applyEntry(session.state, entry)
    if (session.store.appendRoster(session.state.head, entry, wraps, chain) != Appended.Done) reject(ProtocolError.ROSTER_CHAIN)
}

/** Removes [removed] and starts a new epoch it never receives. The store also refuses it at once. */
suspend fun removeDevice(
    session: Session,
    device: MemberSecrets,
    removed: String,
) {
    val state = session.state
    val remaining = state.members.filterKeys { it != removed }.mapValues { (_, member) -> member.kem }
    val epoch = newEpoch(session.group, state.epoch + 1, remaining, session.currentKey())
    val cut =
        session.db
            .groups()
            .streams()
            .firstOrNull { it.did == removed }
            ?.let { mapOf(removed to Cut(it.seq, it.hash)) }
    val draft = Draft(Op.REMOVE, SyncBytes.toHex(device.did), remove = listOf(removed), cut = cut, epoch = epoch.field)
    appendChecked(session, buildEntry(session.group, state, draft, listOf(device)), epoch.wraps, epoch.chain)
}

/**
 * Leaves the group (spec/protocol.md, Leaving): an entry that removes only this device and changes no
 * keys, so it never holds the key of the epoch that shuts it out. The devices that stay rotate.
 */
suspend fun leaveGroup(
    session: Session,
    device: MemberSecrets,
) {
    val did = SyncBytes.toHex(device.did)
    val own = session.record
    val cut = if (own.ownSeq > 0) mapOf(did to Cut(own.ownSeq, own.ownPrev)) else null
    appendChecked(
        session,
        buildEntry(session.group, session.state, Draft(Op.LEAVE, did, remove = listOf(did), cut = cut), listOf(device)),
        emptyMap(),
        null,
    )
}

/**
 * Starts the epoch a device that left never receives: a wrap for every current member, the recovery
 * member included, and the history chain box. False when another device's entry got there first;
 * the next read adopts it instead (spec/protocol.md, Forks).
 */
suspend fun rotateEpoch(
    session: Session,
    device: MemberSecrets,
): Boolean {
    val state = session.state
    val epoch = newEpoch(session.group, state.epoch + 1, state.members.mapValues { (_, member) -> member.kem }, session.currentKey())
    val entry = buildEntry(session.group, state, Draft(Op.ROTATE, SyncBytes.toHex(device.did), epoch = epoch.field), listOf(device))
    applyEntry(state, entry)
    return session.store.appendRoster(state.head, entry, epoch.wraps, epoch.chain) == Appended.Done
}

/** Gives a device a new name; any device of the group may rename any other. */
suspend fun renameDevice(
    session: Session,
    device: MemberSecrets,
    did: String,
    meta: DeviceMeta,
) {
    val state = session.state
    val sealed = sealDeviceMeta(session.currentKey(), session.group, state.epoch, state.seq + 1, SyncBytes.fromHex(did), metaJson(meta))
    val draft = Draft(Op.RENAME, SyncBytes.toHex(device.did), meta = mapOf(did to SyncBytes.b64urlEncode(sealed)))
    appendChecked(session, buildEntry(session.group, state, draft, listOf(device)), emptyMap(), null)
}
