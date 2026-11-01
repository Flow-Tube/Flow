package io.github.aedev.flow.sync2.engine

import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync2.changes.IncomingBatch
import io.github.aedev.flow.sync2.changes.parseBatch
import io.github.aedev.flow.sync2.crypto.ProtocolError
import io.github.aedev.flow.sync2.crypto.decodePayload
import io.github.aedev.flow.sync2.crypto.openBlob
import io.github.aedev.flow.sync2.crypto.purposeKey
import io.github.aedev.flow.sync2.crypto.reject
import io.github.aedev.flow.sync2.store.ObjectType
import io.github.aedev.flow.sync2.store.StorePaths
import io.github.aedev.flow.sync2.store.StreamPosition

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
        if (changes.isEmpty()) break
        changes.mapNotNull { StorePaths.parseLog(it.path) }.forEach { (did, seq) -> logs.getOrPut(did) { ArrayList() } += seq }
        cursor = next
    }
    logs.remove(me)
    val first = logs.mapValues { (_, seqs) -> seqs.min() }
    if (first.values.all { it == 1L }) return null

    val best = HashMap<String?, Found>()
    for ((did, seqs) in logs) {
        for (found in newestManifests(did, seqs.sorted(), links)) {
            val link = found.manifest.link
            if (found.manifest.covers(first) && best[link].let { it == null || it.manifest.merged() < found.manifest.merged() }) {
                best[link] = found
            }
        }
    }
    if (best.isEmpty()) return null
    val positions =
        best.values
            .map { found ->
                found.manifest.vv.filterKeys { it != me }.mapValues { (_, tip) ->
                    val hash =
                        runCatching { SyncBytes.fromHex(tip.hash) }.getOrNull()?.takeIf { it.size == 32 } ?: reject(ProtocolError.PREV)
                    StreamPosition(tip.seq, hash)
                }
            }.reduce { lowest, next ->
                // A log one snapshot leaves out is read from its first object.
                lowest.filterKeys { it in next }.mapValues { (did, at) -> minOf(at, next.getValue(did), compareBy { it.seq }) }
            }
    val floors = best.flatMap { (link, found) -> found.manifest.vv.map { (did, tip) -> (link to did) to tip.seq } }.toMap()
    val batches = HashMap<String, MutableList<IncomingBatch>>()
    for (found in best.values) batches.getOrPut(found.did) { ArrayList() } += snapshotBatches(found.did, found.epoch, found.manifest)
    return Start(batches, positions, floors)
}

/** The newest snapshot manifest of each of [links] (every link when null) in [did]'s log, searched from its last object back. */
private suspend fun Session.newestManifests(
    did: String,
    seqs: List<Long>,
    links: Set<String?>?,
): List<Found> {
    val found = HashMap<String?, Found>()
    for (seq in seqs.asReversed()) {
        if (links != null && found.keys.containsAll(links)) break
        val parsed = verified(store.getObject(StorePaths.log(did, seq)) ?: continue, did, seq)
        if (parsed.header.type != ObjectType.SNAPSHOT) continue
        val manifest = (openContent(parsed) as Content.Snapshot).manifest
        if ((links == null || manifest.link in links) &&
            manifest.link !in found
        ) {
            found[manifest.link] = Found(did, parsed.header.epoch, manifest)
        }
    }
    return found.values.toList()
}

internal suspend fun Session.snapshotBatches(
    writer: String,
    epoch: Int,
    manifest: Manifest,
): List<IncomingBatch> {
    val key = purposeKey(epochKey(epoch), group, epoch, ObjectType.SNAPSHOT.purpose)
    return manifest.parts.map { part ->
        val blob = store.getObject(StorePaths.blob(writer, part.blob)) ?: reject(ProtocolError.BLOB_HASH)
        val hash = runCatching { SyncBytes.fromHex(part.blob) }.getOrNull() ?: reject(ProtocolError.BLOB_HASH)
        parseBatch(decodePayload(openBlob(key, group, epoch, SyncBytes.fromHex(writer), blob, hash)))
    }
}
