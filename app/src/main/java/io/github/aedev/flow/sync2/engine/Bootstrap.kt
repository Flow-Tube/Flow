package io.github.aedev.flow.sync2.engine

import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync2.changes.IncomingBatch
import io.github.aedev.flow.sync2.changes.parseBatch
import io.github.aedev.flow.sync2.crypto.ProtocolError
import io.github.aedev.flow.sync2.crypto.ProtocolException
import io.github.aedev.flow.sync2.crypto.decodePayload
import io.github.aedev.flow.sync2.crypto.openBlob
import io.github.aedev.flow.sync2.crypto.purposeKey
import io.github.aedev.flow.sync2.crypto.reject
import io.github.aedev.flow.sync2.store.ObjectType
import io.github.aedev.flow.sync2.store.ParsedObject
import io.github.aedev.flow.sync2.store.StorePaths
import io.github.aedev.flow.sync2.store.StreamPosition
import java.security.MessageDigest

/** The snapshots a device starts from: their records by writer, and where each log goes on. */
internal class Start(
    val batches: Map<String, List<IncomingBatch>>,
    val positions: Map<String, StreamPosition>,
    /** How far each link's snapshot includes each log: that link's batches up to there are in it already. */
    val floors: Map<Pair<String?, String>, Long>,
)

/** A snapshot manifest found in [did]'s log, written in [epoch]. */
private class Found(
    val did: String,
    val epoch: Int,
    val manifest: Manifest,
)

/** How many objects of all logs the snapshot includes. */
internal fun Manifest.merged(): Long = vv.values.sumOf { it.seq }

/** Whether the manifest covers every log that no longer starts at its first object. */
private fun Manifest.covers(first: Map<String, Long>): Boolean =
    first.filterValues { it > 1 }.all { (did, seq) ->
        (vv[did]?.seq ?: -1) + 1 >=
            seq
    }

/**
 * Where a device that has read nothing starts (spec/protocol.md, Snapshots): `null` when every log
 * still starts at its first object, or when no snapshot of [links] (every link when null) covers the logs that
 * don't. With several links, each log goes on from the lowest place their snapshots name, chained to its hash.
 */
internal suspend fun Session.bootstrap(links: Set<String?>?): Start? {
    val logs = HashMap<String, MutableList<Long>>()
    var cursor = 0L
    while (true) {
        val (changes, next) = store.changes(cursor)
        changes.mapNotNull { StorePaths.parseLog(it.path) }.forEach { (did, seq) -> logs.getOrPut(did) { ArrayList() } += seq }
        // A cursor that doesn't move would hand out the same page forever.
        if (changes.isEmpty() || next <= cursor) break
        cursor = next
    }
    logs.remove(me)
    val first = logs.mapValues { (_, seqs) -> seqs.min() }
    if (first.values.all { it == 1L }) return null

    val candidates = HashMap<String?, MutableList<Found>>()
    for ((did, seqs) in logs) {
        for (found in manifests(did, seqs.sorted(), links)) {
            if (found.manifest.covers(first)) candidates.getOrPut(found.manifest.link) { ArrayList() } += found
        }
    }
    val best = HashMap<String?, Found>()
    val starts = ArrayList<Map<String, StreamPosition>>()
    val batches = HashMap<String, MutableList<IncomingBatch>>()
    for ((link, found) in candidates) {
        // The newest snapshot of the link this device can open (spec/protocol.md, Reading a link from its snapshot).
        for (candidate in found.sortedByDescending { it.manifest.merged() }) {
            val (at, read) =
                try {
                    positions(candidate.manifest) to snapshotBatches(candidate.did, candidate.epoch, candidate.manifest)
                } catch (_: ProtocolException) {
                    continue
                }
            best[link] = candidate
            starts += at
            batches.getOrPut(candidate.did) { ArrayList() } += read
            break
        }
    }
    if (best.isEmpty()) return null
    val positions =
        starts.reduce { lowest, next ->
            // A log one snapshot leaves out is read from its first object.
            lowest.filterKeys { it in next }.mapValues { (did, at) -> minOf(at, next.getValue(did), compareBy { it.seq }) }
        }
    val floors = best.flatMap { (link, found) -> found.manifest.vv.map { (did, tip) -> (link to did) to tip.seq } }.toMap()
    return Start(batches, positions, floors)
}

/** Where each other log goes on after [manifest]'s snapshot, chained to the hash it names. */
private fun Session.positions(manifest: Manifest): Map<String, StreamPosition> =
    manifest.vv.filterKeys { it != me }.mapValues { (_, tip) ->
        val hash = runCatching { SyncBytes.fromHex(tip.hash) }.getOrNull()?.takeIf { it.size == 32 } ?: reject(ProtocolError.PREV)
        StreamPosition(tip.seq, hash)
    }

/** A removed sender's manifest counts only if the walk down from its cut reaches it. */
private suspend fun Session.checkManifestChain(
    did: String,
    parsed: ParsedObject,
) {
    if (state.senders[did]?.until == null) return
    val seq = parsed.header.seq
    if (!MessageDigest.isEqual(walkCut(did, seq - 1, null)[seq], parsed.hash)) reject(ProtocolError.PREV)
}

/** Every snapshot manifest of [links] (every link when null) in [did]'s log. */
private suspend fun Session.manifests(
    did: String,
    seqs: List<Long>,
    links: Set<String?>?,
): List<Found> =
    seqs.mapNotNull { seq ->
        val bytes = store.getObject(StorePaths.log(did, seq)) ?: return@mapNotNull null
        try {
            val parsed = verified(bytes, did, seq)
            if (parsed.header.type != ObjectType.SNAPSHOT) return@mapNotNull null
            checkManifestChain(did, parsed)
            val manifest = (openContent(parsed) as Content.Snapshot).manifest
            Found(did, parsed.header.epoch, manifest).takeIf { links == null || manifest.link in links }
        } catch (_: ProtocolException) {
            null
        }
    }

internal suspend fun Session.snapshotBatches(
    writer: String,
    epoch: Int,
    manifest: Manifest,
): List<IncomingBatch> =
    manifest.parts.map { part ->
        val blob = store.getObject(StorePaths.blob(writer, part.blob)) ?: reject(ProtocolError.BLOB_HASH)
        val hash = runCatching { SyncBytes.fromHex(part.blob) }.getOrNull() ?: reject(ProtocolError.BLOB_HASH)
        val sealedBy = SyncBytes.fromHex(writer)
        val payload =
            openUnder(epoch) { openBlob(purposeKey(it, group, epoch, ObjectType.SNAPSHOT.purpose), group, epoch, sealedBy, blob, hash) }
        parseBatch(decodePayload(payload))
    }
