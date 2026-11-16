package io.github.aedev.flow.sync2.roster

import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.protocol.SyncSerialization
import io.github.aedev.flow.sync2.crypto.ProtocolError
import io.github.aedev.flow.sync2.crypto.groupId
import io.github.aedev.flow.sync2.crypto.isValidSigningKey
import io.github.aedev.flow.sync2.crypto.memberId
import io.github.aedev.flow.sync2.crypto.reject
import io.github.aedev.flow.sync2.crypto.sha256
import io.github.aedev.flow.sync2.crypto.tag
import io.github.aedev.flow.sync2.crypto.verify
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
enum class Role {
    @SerialName("device")
    DEVICE,

    @SerialName("recovery")
    RECOVERY,
}

@Serializable
enum class Op(
    val rank: Int,
) {
    @SerialName("remove")
    REMOVE(0),

    @SerialName("leave")
    LEAVE(1),

    @SerialName("recovery")
    RECOVERY(2),

    @SerialName("restore")
    RESTORE(3),

    @SerialName("rotate")
    ROTATE(4),

    @SerialName("add")
    ADD(5),

    @SerialName("rename")
    RENAME(6),

    @SerialName("genesis")
    GENESIS(7),
}

@Serializable
data class Subject(
    val did: String,
    val sig: String,
    val kem: String,
    val role: Role,
)

@Serializable
data class EpochField(
    val n: Int,
    val kc: String,
    val to: List<String>,
)

/** The last object of a removed device's log that its removal accepts. */
@Serializable
data class Cut(
    val seq: Long,
    val hash: String,
)

@Serializable
data class Entry(
    val v: Int,
    val group: String,
    val seq: Long,
    val prev: String,
    val op: Op,
    val signer: String,
    val add: List<Subject>? = null,
    val remove: List<String>? = null,
    val cut: Map<String, Cut>? = null,
    val epoch: EpochField? = null,
    val meta: Map<String, String>? = null,
    val sigs: Map<String, String>,
)

class Member(
    val sig: ByteArray,
    val kem: ByteArray,
    val role: Role,
    val used: Boolean,
    /** For a recovery member from a `recovery` or `remove` entry: the device that signed it. */
    val installer: String? = null,
)

/** A member of any time: what step 2 of reading an envelope checks a sender against. */
class Sender(
    val sig: ByteArray,
    val role: Role,
    /** The epoch in force when it was added. */
    val since: Int,
    /** The last epoch it could write under, once it was removed or left. */
    val until: Int? = null,
    /** Absent when its removal accepts none of its log. */
    val cut: Cut? = null,
)

class RosterState(
    val group: String,
    val seq: Long,
    val head: String,
    val epoch: Int,
    val kc: String,
    val members: Map<String, Member>,
    val senders: Map<String, Sender>,
    /** A device left during this epoch, so the next device to write appends a `rotate` first. */
    val rotate: Boolean = false,
    /** The device that set the recovery key is gone, so it must be replaced before anything is written. */
    val recoveryDue: Boolean = false,
)

// Strict: an unknown field is a malformed entry; absent fields are omitted, never null.
private val json = Json { explicitNulls = false }

fun entryJson(entry: Entry): JsonElement = json.encodeToJsonElement(Entry.serializer(), entry)

fun canonicalEntry(entry: Entry): String = SyncSerialization.canonicalJson(entryJson(entry))

private fun isHex(
    value: String,
    bytes: Int,
): Boolean = value.length == bytes * 2 && value.all { it in '0'..'9' || it in 'a'..'f' }

/** Strict unpadded base64url: only text that re-encodes to itself. */
fun b64(
    value: String,
    size: Int,
): ByteArray? =
    runCatching { SyncBytes.b64urlDecode(value) }
        .getOrNull()
        ?.takeIf { it.size == size && SyncBytes.b64urlEncode(it) == value }

/** A `meta` value, which is opaque past this: trailing bits and length stay unchecked, so it isn't decoded here (spec/protocol.md, Roster). */
private val META_VALUE = Regex("[A-Za-z0-9_-]+")

/** The largest integer every implementation reads exactly. */
private const val MAX_SEQ = (1L shl 53) - 1

/**
 * Step 2 of reading an envelope: a device, within the epochs it belonged to, and no further along its log than its
 * removal accepted.
 */
fun checkSender(
    senders: Map<String, Sender>,
    did: String,
    epoch: Int,
    seq: Long,
): Sender {
    val sender = senders[did] ?: reject(ProtocolError.SENDER)
    val gone = sender.until != null && (epoch > sender.until || seq > (sender.cut?.seq ?: 0))
    if (sender.role != Role.DEVICE || epoch < sender.since || gone) reject(ProtocolError.SENDER)
    return sender
}

private fun wellFormed(entry: Entry): Boolean {
    val dids = { list: List<String> -> list.isNotEmpty() && list.all { isHex(it, 16) } }
    return entry.v == 2 &&
        entry.seq >= 0 &&
        isHex(entry.group, 16) &&
        isHex(entry.prev, 32) &&
        isHex(entry.signer, 16) &&
        (
            entry.add?.let { add ->
                add.isNotEmpty() &&
                    add.all {
                        isHex(
                            it.did,
                            16,
                        ) && b64(it.sig, 32) != null && b64(it.kem, 32) != null
                    }
            }
                ?: true
        ) &&
        (entry.remove?.let(dids) ?: true) &&
        (
            entry.cut?.let { cut ->
                cut.isNotEmpty() && cut.all { (did, at) -> isHex(did, 16) && isHex(at.hash, 32) && at.seq in 1..MAX_SEQ }
            } ?: true
        ) &&
        (entry.epoch?.let { it.n >= 0 && b64(it.kc, 32) != null && dids(it.to) } ?: true) &&
        (
            entry.meta?.let { meta ->
                meta.isNotEmpty() && meta.all { (did, sealed) -> isHex(did, 16) && META_VALUE.matches(sealed) }
            } ?: true
        ) &&
        entry.sigs.all { (did, signature) -> isHex(did, 16) && b64(signature, 64) != null }
}

/**
 * Accepts only an entry that re-serializes to exactly the canonical JSON it came from, so no two
 * implementations can read one entry differently.
 */
fun parseEntry(value: JsonElement): Entry {
    if (value !is JsonObject) reject(ProtocolError.ROSTER_FORMAT)
    val entry =
        try {
            json.decodeFromJsonElement(Entry.serializer(), value)
        } catch (_: SerializationException) {
            reject(ProtocolError.ROSTER_FORMAT)
        } catch (_: IllegalArgumentException) {
            reject(ProtocolError.ROSTER_FORMAT)
        }
    if (!wellFormed(entry) || canonicalEntry(entry) != SyncSerialization.canonicalJson(value)) {
        reject(ProtocolError.ROSTER_FORMAT)
    }
    return entry
}

fun signingBytes(entry: Entry): ByteArray =
    tag("roster") + SyncSerialization.canonicalJson(JsonObject(entryJson(entry) as JsonObject - "sigs")).toByteArray()

fun entryHash(entry: Entry): String = SyncBytes.toHex(sha256(canonicalEntry(entry).toByteArray()))

private fun strictlySorted(values: List<String>): Boolean = values.zipWithNext().all { (a, b) -> a < b }

/** The op table from protocol.md: how many subjects an op adds, and whether it removes or starts an epoch. */
private fun checkShape(entry: Entry) {
    val (add, remove, epoch) =
        when (entry.op) {
            Op.GENESIS -> Triple(2, false, true)

            Op.ADD -> Triple(1, false, false)

            Op.RESTORE -> Triple(1, false, true)

            // Takes a subject when it replaces the recovery key too, which checkReplacement decides.
            Op.REMOVE -> Triple(entry.add?.size ?: 0, true, true)

            Op.RENAME -> Triple(0, false, false)

            Op.RECOVERY -> Triple(1, true, true)

            Op.LEAVE -> Triple(0, true, false)

            Op.ROTATE -> Triple(0, false, true)
        }
    entry.cut?.let { cut ->
        val removes = entry.op == Op.REMOVE || entry.op == Op.LEAVE
        if (!removes || !entry.remove.orEmpty().containsAll(cut.keys)) reject(ProtocolError.ROSTER_OP)
    }
    val added = entry.add?.size ?: 0
    if (added != add || remove != (entry.remove != null) || epoch != (entry.epoch != null)) reject(ProtocolError.ROSTER_OP)
    val sorted =
        strictlySorted(entry.add.orEmpty().map { it.did }) &&
            strictlySorted(entry.remove.orEmpty()) &&
            strictlySorted(entry.epoch?.to.orEmpty())
    if (!sorted) reject(ProtocolError.ROSTER_FORMAT)
    val roles =
        entry.add
            .orEmpty()
            .map { it.role }
            .sorted()
    val expected =
        when (entry.op) {
            Op.GENESIS -> listOf(Role.DEVICE, Role.RECOVERY)
            Op.RECOVERY, Op.REMOVE -> listOf(Role.RECOVERY)
            else -> listOf(Role.DEVICE)
        }
    if (added > 0 && roles != expected) reject(ProtocolError.ROSTER_OP)
}

private fun checkSubject(
    subject: Subject,
    senders: Map<String, Sender>,
): Member {
    val sig = b64(subject.sig, 32) ?: reject(ProtocolError.ROSTER_FORMAT)
    val kem = b64(subject.kem, 32) ?: reject(ProtocolError.ROSTER_FORMAT)
    if (!isValidSigningKey(sig) || SyncBytes.toHex(memberId(sig, kem)) != subject.did || subject.did in senders) {
        reject(ProtocolError.ROSTER_KEY)
    }
    return Member(sig, kem, subject.role, used = false)
}

private fun checkSigner(
    entry: Entry,
    state: RosterState?,
    added: Map<String, Member>,
): Member {
    val signer =
        if (entry.op == Op.GENESIS) {
            added[entry.signer]?.takeIf { it.role == Role.DEVICE }
        } else {
            val wanted = if (entry.op == Op.RESTORE) Role.RECOVERY else Role.DEVICE
            state?.members?.get(entry.signer)?.takeIf { it.role == wanted && !it.used }
        }
    return signer ?: reject(ProtocolError.ROSTER_SIGNER)
}

/** The current recovery key's id and member. */
fun recoveryMember(members: Map<String, Member>): Map.Entry<String, Member>? =
    members.entries.firstOrNull { it.value.role == Role.RECOVERY }

/** The current recovery key was set by a device that is gone, or goes with [removed]. */
fun installerGone(
    members: Map<String, Member>,
    removed: Collection<String> = emptyList(),
): Boolean {
    val installer = recoveryMember(members)?.value?.installer ?: return false
    return installer !in members || installer in removed
}

/** A removal replaces the recovery key exactly when the device that set it is gone. */
private fun checkReplacement(
    entry: Entry,
    state: RosterState?,
) {
    if (state == null) return
    if (entry.op == Op.ROTATE && state.recoveryDue) reject(ProtocolError.ROSTER_OP)
    if (entry.op != Op.REMOVE) return
    val removed = entry.remove.orEmpty()
    val required = installerGone(state.members, removed)
    val names = recoveryMember(state.members)?.key in removed
    if (required != (entry.add != null) || required != names) reject(ProtocolError.ROSTER_OP)
}

/** The current devices [removed] added, following those devices' additions in turn. */
fun devicesAddedBy(
    entries: List<Entry>,
    removed: String,
): List<String> {
    val found = LinkedHashSet<String>()
    val from = ArrayDeque(listOf(removed))
    while (from.isNotEmpty()) {
        val signer = from.removeFirst()
        for (entry in entries.filter { it.op == Op.ADD && it.signer == signer }) {
            for (subject in entry.add.orEmpty()) if (found.add(subject.did)) from += subject.did
        }
    }
    val members = replayRoster(entries).members
    return found.filter { it in members }
}

/** A genesis must carry the id its keys commit to (spec/crypto.md, Group id). */
private fun checkGroupId(entry: Entry) {
    fun keys(role: Role): Pair<ByteArray, ByteArray> {
        val subject = entry.add.orEmpty().first { it.role == role }
        return SyncBytes.b64urlDecode(subject.sig) to SyncBytes.b64urlDecode(subject.kem)
    }
    if (SyncBytes.toHex(groupId(keys(Role.DEVICE), keys(Role.RECOVERY))) != entry.group) reject(ProtocolError.ROSTER_GROUP)
}

private fun checkChain(
    state: RosterState?,
    entry: Entry,
) {
    val linked =
        if (state != null) {
            entry.group == state.group && entry.seq == state.seq + 1 && entry.prev == state.head && entry.op != Op.GENESIS
        } else {
            entry.op == Op.GENESIS && entry.seq == 0L && entry.prev == SyncBytes.toHex(ByteArray(32))
        }
    if (!linked) reject(ProtocolError.ROSTER_CHAIN)
}

/** Applies one entry to the state before it, or says why it is not valid. */
fun applyEntry(
    state: RosterState?,
    entry: Entry,
): RosterState {
    checkChain(state, entry)
    checkShape(entry)
    if (entry.op == Op.GENESIS) checkGroupId(entry)
    checkReplacement(entry, state)

    val senders = state?.senders.orEmpty().toMutableMap()
    val added = entry.add.orEmpty().associate { it.did to checkSubject(it, senders) }

    val signer = checkSigner(entry, state, added)
    if (entry.sigs.keys != setOf(entry.signer) + added.keys) reject(ProtocolError.ROSTER_SIGNATURE)
    val message = signingBytes(entry)
    for ((id, signature) in entry.sigs) {
        val key = if (id == entry.signer) signer.sig else added.getValue(id).sig
        if (!verify(key, message, b64(signature, 64) ?: reject(ProtocolError.ROSTER_SIGNATURE))) reject(ProtocolError.ROSTER_SIGNATURE)
    }

    val members = state?.members.orEmpty().toMutableMap()
    val replaces = entry.op == Op.RECOVERY || (entry.op == Op.REMOVE && entry.add != null)
    // A leave removes exactly its signer; every other removal removes someone else.
    for (id in entry.remove.orEmpty()) {
        val member = members[id] ?: reject(ProtocolError.ROSTER_OP)
        if ((id == entry.signer) != (entry.op == Op.LEAVE)) reject(ProtocolError.ROSTER_OP)
        val recovery = member.role == Role.RECOVERY
        if (recovery && (!replaces || entry.cut?.containsKey(id) == true)) reject(ProtocolError.ROSTER_OP)
        if (!recovery && entry.op == Op.RECOVERY) reject(ProtocolError.ROSTER_OP)
        members.remove(id)
        senders[id]?.let { senders[id] = Sender(it.sig, it.role, it.since, state?.epoch, entry.cut?.get(id)) }
    }
    if (entry.op == Op.LEAVE && members.values.none { it.role == Role.DEVICE }) reject(ProtocolError.ROSTER_OP)
    if (entry.op ==
        Op.RESTORE
    ) {
        members[entry.signer] = Member(signer.sig, signer.kem, signer.role, used = true, installer = signer.installer)
    }
    val inForce = entry.epoch?.n ?: state?.epoch ?: 0
    added.forEach { (id, member) ->
        senders[id] = Sender(member.sig, member.role, inForce)
        val installer = if (member.role == Role.RECOVERY && entry.op != Op.GENESIS) entry.signer else null
        members[id] = Member(member.sig, member.kem, member.role, member.used, installer)
    }

    val meta = entry.meta
    if (meta != null) {
        val renamesOneDevice = meta.size == 1 && meta.keys.all { members[it]?.role == Role.DEVICE }
        if (!members.keys.containsAll(meta.keys) || (entry.op == Op.RENAME && !renamesOneDevice)) reject(ProtocolError.ROSTER_META)
    } else if (entry.op == Op.RENAME) {
        reject(ProtocolError.ROSTER_META)
    }

    val epoch = entry.epoch
    if (epoch != null) {
        val expected = state?.let { it.epoch + 1 } ?: 0
        if (epoch.n != expected || epoch.to != members.keys.sorted()) reject(ProtocolError.ROSTER_EPOCH)
    }

    return RosterState(
        group = entry.group,
        seq = entry.seq,
        head = entryHash(entry),
        epoch = inForce,
        kc = epoch?.kc ?: state?.kc.orEmpty(),
        members = members.toSortedMap(),
        senders = senders,
        rotate = entry.op == Op.LEAVE || (epoch == null && state?.rotate == true),
        recoveryDue = installerGone(members),
    )
}

fun replayRoster(entries: List<Entry>): RosterState =
    entries.fold(null as RosterState?) { state, entry -> applyEntry(state, entry) } ?: reject(ProtocolError.ROSTER_CHAIN)

/**
 * Picks the winner among valid entries competing for the same `seq` on a store without ordering:
 * removal first, then the lowest entry hash.
 */
fun forkWinner(candidates: List<Entry>): Entry? = candidates.minWithOrNull(compareBy({ it.op.rank }, { entryHash(it) }))
