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
import io.github.aedev.flow.sync2.data.keptDevices
import io.github.aedev.flow.sync2.engine.EpochKeys
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

/** A current device a restore can keep or sign out; [name] is empty when it can't be read. */
data class RestoreDevice(
    val did: String,
    val name: String,
    val platform: String,
)

/** The roster a store shows the recovery member, and the current key it opens. */
private class RecoveryView(
    val entries: List<Entry>,
    val state: RosterState,
    val group: ByteArray,
    val key: ByteArray,
)

private suspend fun readAsRecovery(
    asRecovery: Store,
    recovery: MemberSecrets,
): RecoveryView {
    val entries = asRecovery.rosterAfter(-1)
    val state = replayRoster(entries)
    val recoveryId = SyncBytes.toHex(recovery.did)
    if (state.members[recoveryId]?.used != false) throw LinkedException.RecoveryUsed()
    val group = SyncBytes.fromHex(state.group)
    val commitment = SyncBytes.b64urlDecode(state.kc)
    val wrap = asRecovery.key(state.epoch, kcName(commitment), recoveryId) ?: reject(ProtocolError.KEY_COMMITMENT)
    val current = unwrapEpochKey(recovery.kemSecret, group, state.epoch, recovery.did, wrap)
    checkEpochKey(current, group, state.epoch, commitment)
    return RecoveryView(entries, state, group, current)
}

/** The devices a restore asks about (spec/protocol.md, Recovery, step 3). It writes nothing. */
suspend fun previewRestore(
    asRecovery: Store,
    recovery: MemberSecrets,
): List<RestoreDevice> {
    val view = readAsRecovery(asRecovery, recovery)
    val commitments = view.entries.mapNotNull { it.epoch }.associate { it.n to SyncBytes.b64urlDecode(it.kc) }
    val keys = EpochKeys(asRecovery, view.group, commitments).also { it[view.state.epoch] = view.key }
    val names = deviceNames(view.entries, view.group, keys::key)
    return view.state.members
        .filterValues { it.role == Role.DEVICE }
        .keys
        .sorted()
        .map { did -> RestoreDevice(did, names[did]?.name.orEmpty(), names[did]?.platform.orEmpty()) }
}

/** The devices a restore has yet to sign out: all current ones but this one and those the user kept. */
internal fun Session.unchosenDevices(): List<String> {
    val keep = record.keptDevices ?: return emptyList()
    return state.members
        .filter { (did, member) -> member.role == Role.DEVICE && did != me && did !in keep }
        .keys
        .sorted()
}

/**
 * Signs out the devices the user didn't choose (spec/protocol.md, Recovery, step 6), having read every log without
 * writing, so that nothing is sealed under a key they hold. [reread] opens the roster again from the store. A removal
 * that lost a fork stays due for the next sync, and so does one that dies on the way: it starts over from the roster.
 * New words the removal had to make go to [holdWords] before the roster is read again, which can fail. Returns the
 * session to go on with.
 */
suspend fun finishRestore(
    session: Session,
    device: MemberSecrets,
    holdWords: (String) -> Unit,
    reread: suspend () -> Session,
): Session {
    var current = session
    val unchosen = current.unchosenDevices()
    if (unchosen.isNotEmpty()) {
        removeDevices(current, device, unchosen, current.readablePositions())?.let { words ->
            holdWords(words)
            current.db.groups().setRecoverySaved(false)
        }
        current.db.snapshots().snapshotSoon()
        current = reread()
    }
    if (current.unchosenDevices().isEmpty()) {
        current.db.groups().forgetRestoreKeep()
        current = reread()
    }
    return current
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
    val view = readAsRecovery(asRecovery, recovery)
    var state = view.state
    val recoveryId = SyncBytes.toHex(recovery.did)
    val group = view.group
    val current = view.key

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

/** The devices of the group on the Flow server at [storeUrl], for the user to choose from. */
suspend fun previewServerRestore(
    storeUrl: String,
    words: String,
): List<RestoreDevice> = previewOn(words) { ServerStore.connect(storeUrl, it, null, null) }

/** The devices of the group in a WebDAV folder, for the user to choose from. */
suspend fun previewWebDavRestore(
    files: WebDavStore,
    words: String,
): List<RestoreDevice> = previewOn(words) { FileLayout(files, SyncBytes.toHex(it.did)) }

private suspend fun previewOn(
    words: String,
    store: (recovery: MemberSecrets) -> Store,
): List<RestoreDevice> {
    val secret = recoverySecret(words) ?: throw LinkedException.WrongWords()
    val recovery = recoveryKeys(secret)
    return previewRestore(store(recovery), recovery).also { recovery.wipe() }
}

/**
 * Restores onto this device from the recovery words, keeping the devices in [keep]. Its profile link stays open until
 * the first sync reads every log ([io.github.aedev.flow.sync2.service.LinkedProfiles.links]), which also signs out
 * every other device ([finishRestore]).
 */
suspend fun restoreGroup(
    db: LinkedDatabase,
    storeUrl: String,
    words: String,
    device: MemberSecrets,
    meta: DeviceMeta,
    keep: Set<String>,
): String {
    val asDevice = ServerStore.connect(storeUrl, device, null, null)
    var asRecovery: ServerStore? = null
    return restoreOn(db, words, device, meta, keep, { recovery ->
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
    keep: Set<String>,
): String {
    val layout = FileLayout(files, SyncBytes.toHex(device.did))
    return restoreOn(db, words, device, meta, keep, { layout to layout }) { state ->
        GroupRecord(state.group, WEBDAV_STORE + files.location, null, link = "")
    }
}

/** Restores through [stores], the store as the recovery member and as [device], and saves the [record] it names. */
private suspend fun restoreOn(
    db: LinkedDatabase,
    words: String,
    device: MemberSecrets,
    meta: DeviceMeta,
    keep: Set<String>,
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
    db.groups().saveGroup(saved.copy(restoreKeep = keep.sorted().joinToString(",")))
    return restored.words
}
