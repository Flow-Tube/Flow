package io.github.aedev.flow.sync2.linking

import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.crypto.SyncCrypto
import io.github.aedev.flow.sync2.crypto.MemberSecrets
import io.github.aedev.flow.sync2.crypto.ProtocolError
import io.github.aedev.flow.sync2.crypto.recoveryKeys
import io.github.aedev.flow.sync2.crypto.recoveryWords
import io.github.aedev.flow.sync2.crypto.reject
import io.github.aedev.flow.sync2.crypto.sealChain
import io.github.aedev.flow.sync2.crypto.wrapEpochKey
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.roster.Cut
import io.github.aedev.flow.sync2.roster.Draft
import io.github.aedev.flow.sync2.roster.Entry
import io.github.aedev.flow.sync2.roster.EpochField
import io.github.aedev.flow.sync2.roster.Op
import io.github.aedev.flow.sync2.roster.Role
import io.github.aedev.flow.sync2.roster.applyEntry
import io.github.aedev.flow.sync2.roster.buildEntry
import io.github.aedev.flow.sync2.roster.epochDraft
import io.github.aedev.flow.sync2.roster.installerGone
import io.github.aedev.flow.sync2.roster.memberSubject
import io.github.aedev.flow.sync2.roster.recoveryMember
import io.github.aedev.flow.sync2.roster.sealDeviceMeta
import io.github.aedev.flow.sync2.store.Appended
import io.github.aedev.flow.sync2.store.StreamPosition

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

/**
 * Removes [removed] and starts a new epoch it never receives. The store also refuses it at once.
 * Each log's `cut` is what this device saved reading it, unless a caller that saves nothing yet gives [read].
 */
suspend fun removeDevices(
    session: Session,
    device: MemberSecrets,
    removed: List<String>,
    read: Map<String, StreamPosition>? = null,
): String? {
    val state = session.state
    val positions =
        read?.mapValues { (_, at) -> Cut(at.seq, SyncBytes.toHex(at.hash)) }
            ?: session.db
                .groups()
                .streams()
                .associate { it.did to Cut(it.seq, it.hash) }
    val cut = removed.mapNotNull { did -> positions[did]?.takeIf { it.seq > 0 }?.let { did to it } }.toMap()
    val replacing = installerGone(state.members, removed)
    val secret = SyncCrypto.randomBytes(32)
    val next = recoveryKeys(secret)
    val old = recoveryMember(state.members)?.key.takeIf { replacing }
    val gone = removed + listOfNotNull(old)
    val remaining =
        state.members.filterKeys { it !in gone }.mapValues { (_, member) -> member.kem } +
            if (replacing) mapOf(SyncBytes.toHex(next.did) to next.kemPublic) else emptyMap()
    val epoch = newEpoch(session.group, state.epoch + 1, remaining, session.currentKey())
    val draft =
        Draft(
            op = Op.REMOVE,
            signer = SyncBytes.toHex(device.did),
            add = if (replacing) listOf(memberSubject(next.sigPublic, next.kemPublic, Role.RECOVERY)) else null,
            remove = gone,
            cut = cut.ifEmpty { null },
            epoch = epoch.field,
        )
    val signers = if (replacing) listOf(device, next) else listOf(device)
    appendChecked(session, buildEntry(session.group, state, draft, signers), epoch.wraps, epoch.chain)
    next.wipe()
    return if (replacing) recoveryWords(secret).also { secret.fill(0) } else null
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
