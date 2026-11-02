package io.github.aedev.flow.sync2.roster

import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.protocol.SyncSerialization
import io.github.aedev.flow.sync2.crypto.ProtocolError
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
    val epoch: EpochField? = null,
    val meta: Map<String, String>? = null,
    val sigs: Map<String, String>,
)

class Member(
    val sig: ByteArray,
    val kem: ByteArray,
    val role: Role,
    val used: Boolean,
)

class RosterState(
    val group: String,
    val seq: Long,
    val head: String,
    val epoch: Int,
    val kc: String,
    val members: Map<String, Member>,
    val seen: Set<String>,
    /** A device left during this epoch, so the next device to write appends a `rotate` first. */
    val rotate: Boolean = false,
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
        (entry.epoch?.let { it.n >= 0 && b64(it.kc, 32) != null && dids(it.to) } ?: true) &&
        (entry.meta?.all { (did, sealed) -> isHex(did, 16) && sealed.isNotEmpty() } ?: true) &&
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
            Op.REMOVE -> Triple(0, true, true)
            Op.RENAME -> Triple(0, false, false)
            Op.RECOVERY -> Triple(1, true, true)
            Op.LEAVE -> Triple(0, true, false)
            Op.ROTATE -> Triple(0, false, true)
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
            Op.RECOVERY -> listOf(Role.RECOVERY)
            else -> listOf(Role.DEVICE)
        }
    if (added > 0 && roles != expected) reject(ProtocolError.ROSTER_OP)
}

private fun checkSubject(
    subject: Subject,
    seen: Set<String>,
): Member {
    val sig = b64(subject.sig, 32) ?: reject(ProtocolError.ROSTER_FORMAT)
    val kem = b64(subject.kem, 32) ?: reject(ProtocolError.ROSTER_FORMAT)
    if (!isValidSigningKey(sig) || SyncBytes.toHex(memberId(sig, kem)) != subject.did || subject.did in seen) {
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

    val seen = state?.seen.orEmpty().toMutableSet()
    val added = entry.add.orEmpty().associate { it.did to checkSubject(it, seen) }

    val signer = checkSigner(entry, state, added)
    if (entry.sigs.keys != setOf(entry.signer) + added.keys) reject(ProtocolError.ROSTER_SIGNATURE)
    val message = signingBytes(entry)
    for ((id, signature) in entry.sigs) {
        val key = if (id == entry.signer) signer.sig else added.getValue(id).sig
        if (!verify(key, message, b64(signature, 64) ?: reject(ProtocolError.ROSTER_SIGNATURE))) reject(ProtocolError.ROSTER_SIGNATURE)
    }

    val members = state?.members.orEmpty().toMutableMap()
    val removedRole = if (entry.op == Op.RECOVERY) Role.RECOVERY else Role.DEVICE
    // A leave removes exactly its signer; every other removal removes someone else.
    for (id in entry.remove.orEmpty()) {
        if (members[id]?.role != removedRole || (id == entry.signer) != (entry.op == Op.LEAVE)) reject(ProtocolError.ROSTER_OP)
        members.remove(id)
    }
    if (entry.op == Op.LEAVE && members.values.none { it.role == Role.DEVICE }) reject(ProtocolError.ROSTER_OP)
    if (entry.op == Op.RESTORE) members[entry.signer] = Member(signer.sig, signer.kem, signer.role, used = true)
    seen += added.keys
    members += added

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
        epoch = epoch?.n ?: state?.epoch ?: 0,
        kc = epoch?.kc ?: state?.kc.orEmpty(),
        members = members.toSortedMap(),
        seen = seen,
        rotate = entry.op == Op.LEAVE || (epoch == null && state?.rotate == true),
    )
}

fun replayRoster(entries: List<Entry>): RosterState =
    entries.fold(null as RosterState?) { state, entry -> applyEntry(state, entry) } ?: reject(ProtocolError.ROSTER_CHAIN)

/**
 * Picks the winner among valid entries competing for the same `seq` on a store without ordering:
 * removal first, then the lowest entry hash.
 */
fun forkWinner(candidates: List<Entry>): Entry? = candidates.minWithOrNull(compareBy({ it.op.rank }, { entryHash(it) }))

/** The chain every device picks from [entries]: at each `seq`, the winner among the entries valid there (spec/protocol.md, Forks). */
fun resolveForks(entries: List<Entry>): List<Entry> {
    val chain = ArrayList<Entry>()
    var state: RosterState? = null
    for ((_, candidates) in entries.groupBy { it.seq }.toSortedMap()) {
        val winner = forkWinner(candidates.filter { runCatching { applyEntry(state, it) }.isSuccess }) ?: break
        state = applyEntry(state, winner)
        chain += winner
    }
    return chain
}
