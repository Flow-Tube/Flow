package io.github.aedev.flow.sync2.linking

import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.crypto.SyncCrypto
import io.github.aedev.flow.sync2.crypto.MemberSecrets
import io.github.aedev.flow.sync2.crypto.ProtocolError
import io.github.aedev.flow.sync2.crypto.checkEpochKey
import io.github.aedev.flow.sync2.crypto.recoveryKeys
import io.github.aedev.flow.sync2.crypto.recoverySecret
import io.github.aedev.flow.sync2.crypto.recoveryWords
import io.github.aedev.flow.sync2.crypto.reject
import io.github.aedev.flow.sync2.crypto.unwrapEpochKey
import io.github.aedev.flow.sync2.data.GroupRecord
import io.github.aedev.flow.sync2.data.LinkedDatabase
import io.github.aedev.flow.sync2.data.WEBDAV_STORE
import io.github.aedev.flow.sync2.data.addEntries
import io.github.aedev.flow.sync2.engine.LinkedException
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.roster.Draft
import io.github.aedev.flow.sync2.roster.Entry
import io.github.aedev.flow.sync2.roster.Op
import io.github.aedev.flow.sync2.roster.Role
import io.github.aedev.flow.sync2.roster.RosterState
import io.github.aedev.flow.sync2.roster.applyEntry
import io.github.aedev.flow.sync2.roster.buildEntry
import io.github.aedev.flow.sync2.roster.memberSubject
import io.github.aedev.flow.sync2.roster.replayRoster
import io.github.aedev.flow.sync2.roster.sealDeviceMeta
import io.github.aedev.flow.sync2.store.Appended
import io.github.aedev.flow.sync2.store.FileLayout
import io.github.aedev.flow.sync2.store.ServerStore
import io.github.aedev.flow.sync2.store.Store
import io.github.aedev.flow.sync2.store.WebDavStore
import io.github.aedev.flow.sync2.store.kcName

/** The roster after a restore, and the new recovery words to show once. */
class Restored(
    val entries: List<Entry>,
    val words: String,
)

private suspend fun append(
    store: Store,
    state: RosterState,
    entry: Entry,
    wraps: Map<String, String>,
    chain: String,
): RosterState {
    val after = applyEntry(state, entry)
    if (store.appendRoster(state.head, entry, wraps, chain) != Appended.Done) reject(ProtocolError.ROSTER_CHAIN)
    return after
}

/**
 * Restores a group with the recovery key (spec/protocol.md, Recovery): the recovery member adds
 * [device] and starts an epoch, then [device] replaces the used key straight away. [asRecovery] is
 * the store signed in as the recovery member, [asDevice] as the new device.
 */
suspend fun restore(
    asRecovery: Store,
    asDevice: Store,
    recovery: MemberSecrets,
    device: MemberSecrets,
    meta: DeviceMeta,
): Restored {
    var state = replayRoster(asRecovery.rosterAfter(-1))
    val recoveryId = SyncBytes.toHex(recovery.did)
    if (state.members[recoveryId]?.used != false) throw LinkedException.RecoveryUsed()
    val group = SyncBytes.fromHex(state.group)
    val commitment = SyncBytes.b64urlDecode(state.kc)
    val wrap = asRecovery.key(state.epoch, kcName(commitment), recoveryId) ?: reject(ProtocolError.KEY_COMMITMENT)
    val current = unwrapEpochKey(recovery.kemSecret, group, state.epoch, recovery.did, wrap)
    checkEpochKey(current, group, state.epoch, commitment)

    val kems = state.members.mapValues { (_, member) -> member.kem }
    val restored = newEpoch(group, state.epoch + 1, kems + (SyncBytes.toHex(device.did) to device.kemPublic), current)
    val sealedMeta = sealDeviceMeta(restored.key, group, state.epoch + 1, state.seq + 1, device.did, metaJson(meta))
    val restore =
        Draft(
            op = Op.RESTORE,
            signer = recoveryId,
            add = listOf(memberSubject(device.sigPublic, device.kemPublic, Role.DEVICE)),
            epoch = restored.field,
            meta = mapOf(SyncBytes.toHex(device.did) to SyncBytes.b64urlEncode(sealedMeta)),
        )
    state = append(asRecovery, state, buildEntry(group, state, restore, listOf(recovery, device)), restored.wraps, restored.chain)

    val replacement = replacement(group, state, device, restored.key)
    append(asDevice, state, replacement.entry, replacement.epoch.wraps, replacement.epoch.chain)
    return Restored(asDevice.rosterAfter(-1), replacement.words)
}

/** A `recovery` entry that swaps in a new recovery key, the epoch it starts, and the new words. */
private class Replacement(
    val entry: Entry,
    val epoch: NewEpoch,
    val words: String,
)

private fun replacement(
    group: ByteArray,
    state: RosterState,
    device: MemberSecrets,
    currentKey: ByteArray,
): Replacement {
    val secret = SyncCrypto.randomBytes(32)
    val next = recoveryKeys(secret)
    val old =
        state.members
            .filterValues { it.role == Role.RECOVERY }
            .keys
            .sorted()
    val remaining = state.members.filterKeys { it !in old }.mapValues { (_, member) -> member.kem }
    val epoch = newEpoch(group, state.epoch + 1, remaining + (SyncBytes.toHex(next.did) to next.kemPublic), currentKey)
    val change =
        Draft(
            op = Op.RECOVERY,
            signer = SyncBytes.toHex(device.did),
            add = listOf(memberSubject(next.sigPublic, next.kemPublic, Role.RECOVERY)),
            remove = old,
            epoch = epoch.field,
        )
    val entry = buildEntry(group, state, change, listOf(device, next))
    next.wipe()
    return Replacement(entry, epoch, recoveryWords(secret).also { secret.fill(0) })
}

/**
 * Replaces the recovery key from a member device (spec/protocol.md, Recovery), so the old words stop
 * working: the old recovery member gets no key of the epoch this starts. Returns the new words.
 */
suspend fun replaceRecovery(
    session: Session,
    device: MemberSecrets,
): String {
    val replacement = replacement(session.group, session.state, device, session.currentKey())
    appendChecked(session, replacement.entry, replacement.epoch.wraps, replacement.epoch.chain)
    return replacement.words
}

/**
 * Restores onto this device from the recovery words. Its profile link stays open until the first
 * sync reads every log ([io.github.aedev.flow.sync2.service.LinkedProfiles.links]).
 */
suspend fun restoreGroup(
    db: LinkedDatabase,
    storeUrl: String,
    words: String,
    device: MemberSecrets,
    meta: DeviceMeta,
): String {
    val asDevice = ServerStore.connect(storeUrl, device, null, null)
    var asRecovery: ServerStore? = null
    return restoreOn(db, words, device, meta, { recovery ->
        ServerStore.connect(storeUrl, recovery, null, null).also { asRecovery = it } to
            asDevice
    }) {
        val (instance, group) = asDevice.identity()
        if (instance to group != asRecovery?.identity() || group == null) throw LinkedException.Unexpected()
        GroupRecord(group, storeUrl, instance, link = "")
    }
}

/** Restores a group from a WebDAV folder, where the recovery member and [device] write through the same login. */
suspend fun restoreWebDavGroup(
    db: LinkedDatabase,
    files: WebDavStore,
    words: String,
    device: MemberSecrets,
    meta: DeviceMeta,
): String {
    val layout = FileLayout(files, SyncBytes.toHex(device.did))
    return restoreOn(db, words, device, meta, { layout to layout }) { state ->
        GroupRecord(state.group, WEBDAV_STORE + files.location, null, link = "")
    }
}

/** Restores through [stores], the store as the recovery member and as [device], and saves the [record] it names. */
private suspend fun restoreOn(
    db: LinkedDatabase,
    words: String,
    device: MemberSecrets,
    meta: DeviceMeta,
    stores: (recovery: MemberSecrets) -> Pair<Store, Store>,
    record: suspend (RosterState) -> GroupRecord,
): String {
    val secret = recoverySecret(words) ?: throw LinkedException.WrongWords()
    val recovery = recoveryKeys(secret)
    val (asRecovery, asDevice) = stores(recovery)
    val restored = restore(asRecovery, asDevice, recovery, device, meta)
    recovery.wipe()
    val saved = record(replayRoster(restored.entries))
    db.groups().addEntries(restored.entries)
    db.groups().saveGroup(saved)
    return restored.words
}
