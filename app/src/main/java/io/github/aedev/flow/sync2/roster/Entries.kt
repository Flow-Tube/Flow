package io.github.aedev.flow.sync2.roster

import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync2.crypto.MemberSecrets
import io.github.aedev.flow.sync2.crypto.epochCommitment
import io.github.aedev.flow.sync2.crypto.memberId
import io.github.aedev.flow.sync2.crypto.sign

/** What an entry says, before the chain fields and signatures are added. */
data class Draft(
    val op: Op,
    val signer: String,
    val add: List<Subject>? = null,
    val remove: List<String>? = null,
    val cut: Map<String, Cut>? = null,
    val epoch: EpochField? = null,
    val meta: Map<String, String>? = null,
)

fun memberSubject(
    sigPublic: ByteArray,
    kemPublic: ByteArray,
    role: Role,
): Subject =
    Subject(SyncBytes.toHex(memberId(sigPublic, kemPublic)), SyncBytes.b64urlEncode(sigPublic), SyncBytes.b64urlEncode(kemPublic), role)

fun epochDraft(
    epochKey: ByteArray,
    group: ByteArray,
    n: Int,
    members: List<String>,
): EpochField = EpochField(n, SyncBytes.b64urlEncode(epochCommitment(epochKey, group, n)), members.sorted())

/** Adds the signature of [signer] to an entry. */
fun signEntry(
    entry: Entry,
    signer: MemberSecrets,
): Entry =
    entry.copy(sigs = entry.sigs + (SyncBytes.toHex(signer.did) to SyncBytes.b64urlEncode(sign(signer.sigSeed, signingBytes(entry)))))

/** Builds the entry that follows [previous] (the roster head, or none for genesis) and signs it. */
fun buildEntry(
    group: ByteArray,
    previous: RosterState?,
    draft: Draft,
    signers: List<MemberSecrets>,
): Entry {
    val entry =
        Entry(
            v = 2,
            group = SyncBytes.toHex(group),
            seq = previous?.let { it.seq + 1 } ?: 0,
            prev = previous?.head ?: "00".repeat(32),
            op = draft.op,
            signer = draft.signer,
            add = draft.add?.sortedBy { it.did },
            remove = draft.remove?.sorted(),
            cut = draft.cut,
            epoch = draft.epoch,
            meta = draft.meta,
            sigs = emptyMap(),
        )
    return signers.fold(entry, ::signEntry)
}
