package io.github.aedev.flow.sync2.changes

import io.github.aedev.flow.sync.apply.ReceivedCollection
import io.github.aedev.flow.sync.protocol.CollectionWire
import io.github.aedev.flow.sync.protocol.SyncCollection
import io.github.aedev.flow.sync.protocol.SyncSerialization
import io.github.aedev.flow.sync2.data.SentDao
import io.github.aedev.flow.sync2.data.SentRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** FLOW-SYNC/1's chunk size; it keeps every batch well under the 4 MiB object limit. */
private const val RECORDS_PER_BATCH = 1000

/** The brains change with every video watched; spec/protocol.md asks for at most one upload in 6 hours. */
private const val BRAIN_INTERVAL_MS = 6 * 60 * 60 * 1000L

internal val BRAINS = setOf(SyncCollection.FLOW_NEURO_BRAIN, SyncCollection.MUSIC_BRAIN)

/** Whether a brain last uploaded at [sentAt] may go out again at [nowMs]. */
internal fun brainUploadDue(
    sentAt: Long?,
    nowMs: Long,
): Boolean = sentAt == null || nowMs - sentAt >= BRAIN_INTERVAL_MS

@Serializable
private class BatchHeader(
    val collection: String,
    val head: String,
    val link: String?,
)

class Batch(
    val collection: String,
    val content: ByteArray,
)

/** Batches to upload, plus the hashes to record once the upload succeeded. */
class Outgoing(
    val batches: List<Batch>,
    internal val sent: List<SentRecord>,
)

/** A batch read back: its collection, link, the roster head it names, and its records. */
class IncomingBatch(
    val collection: String,
    val link: String?,
    val head: String,
    /** The batch's length in bytes, which counts toward the next snapshot. */
    val size: Int,
    val lines: List<String>,
)

private fun recordKey(
    collection: String,
    record: JsonObject,
): String? {
    fun field(name: String) = record[name]?.jsonPrimitive?.content
    return when (collection) {
        SyncCollection.WATCH_HISTORY -> field("videoId")
        SyncCollection.LIKES -> field("kind")?.let { kind -> field("id")?.let { "$kind:$it" } }
        SyncCollection.PLAYLISTS -> field("syncId")
        SyncCollection.SETTINGS -> field("key")
        SyncCollection.SUBSCRIPTIONS -> field("name")
        SyncCollection.SUBSCRIBED_CHANNELS -> field("channelId")
        SyncCollection.NOTES -> field("id")
        in BRAINS -> ""
        else -> null
    }
}

/** A brain record carries the time it was exported, which says nothing about its content. */
private fun recordHash(
    collection: String,
    record: JsonObject,
    line: String,
): String =
    if (collection in BRAINS) {
        SyncSerialization.sha256Hex(SyncSerialization.canonicalJson(JsonObject(record - "hlc")))
    } else {
        SyncSerialization.sha256Hex(line)
    }

/** A header line and then the records, one per line: the format of change batches and snapshot blobs. */
fun batchContent(
    collection: String,
    head: String,
    link: String?,
    lines: List<String>,
): ByteArray {
    val header = SyncSerialization.canonicalJson(Json.encodeToJsonElement(BatchHeader.serializer(), BatchHeader(collection, head, link)))
    return (listOf(header) + lines).joinToString("\n").toByteArray()
}

/**
 * Keeps the exported records whose canonical form changed since the last upload. [head] is this
 * device's roster head, [link] the profile link id.
 */
suspend fun outgoingBatches(
    sentDao: SentDao,
    exported: Map<String, CollectionWire>,
    head: String,
    link: String,
    nowMs: Long,
): Outgoing {
    val batches = ArrayList<Batch>()
    val sent = ArrayList<SentRecord>()
    for ((collection, wire) in exported) {
        val previous = sentDao.forCollection(link, collection).associateBy { it.recordKey }
        val changed =
            wire.lines.filter { line ->
                val record = Json.parseToJsonElement(line).jsonObject
                val key = recordKey(collection, record) ?: return@filter false
                val hash = recordHash(collection, record, line)
                val last = previous[key]
                val tooSoon = collection in BRAINS && !brainUploadDue(last?.sentAt, nowMs)
                (last?.hash != hash && !tooSoon).also { if (it) sent += SentRecord(link, collection, key, hash, nowMs) }
            }
        changed.chunked(RECORDS_PER_BATCH).mapTo(batches) { Batch(collection, batchContent(collection, head, link, it)) }
    }
    return Outgoing(batches, sent)
}

/** Records what was uploaded; call only after every batch reached the store. */
suspend fun markSent(
    sentDao: SentDao,
    outgoing: Outgoing,
) = sentDao.upsert(outgoing.sent)

fun parseBatch(content: ByteArray): IncomingBatch {
    val lines = content.toString(Charsets.UTF_8).split('\n')
    val header = Json.decodeFromString(BatchHeader.serializer(), lines.first())
    return IncomingBatch(header.collection, header.link, header.head, content.size, lines.drop(1).filter { it.isNotEmpty() })
}

/**
 * Applies batches of [link] from one sender through the FLOW-SYNC/1 merges, which run in one transaction
 * behind a pre-merge backup. What an applied record looks like in this device's own [export] counts
 * as known to the group, so it isn't sent back: another app's canonical form of the same record
 * differs, and sending that back would bounce between the two forever. A record of this device's
 * that changed since its last upload stays unknown, so it still goes out.
 */
suspend fun applyBatches(
    sentDao: SentDao,
    link: String,
    batches: List<IncomingBatch>,
    nowMs: Long,
    export: suspend (collections: List<String>) -> Map<String, CollectionWire>,
    apply: suspend (Map<String, ReceivedCollection>) -> Unit,
) {
    val keys = HashMap<String, MutableSet<String>>()
    val received =
        batches.groupBy { it.collection }.mapValues { (collection, parts) ->
            val lines = parts.flatMap { it.lines }
            for (line in lines) {
                recordKey(collection, Json.parseToJsonElement(line).jsonObject)?.let { keys.getOrPut(collection) { HashSet() } += it }
            }
            ReceivedCollection(lines, SyncSerialization.sha256Hex(lines.joinToString("\n")))
        }
    val collections = keys.keys.toList()
    val before = ownHashes(export(collections), keys)
    apply(received)
    val after = ownHashes(export(collections), keys)
    val known = ArrayList<SentRecord>()
    for ((collection, hashes) in after) {
        val previous = sentDao.forCollection(link, collection).associateBy { it.recordKey }
        for ((key, hash) in hashes) {
            val last = previous[key]?.hash
            val pending = before[collection]?.get(key).let { it != null && it != last }
            if (!pending && hash != last) known += SentRecord(link, collection, key, hash, nowMs)
        }
    }
    sentDao.upsert(known)
}

/** The hash of each of [keys] in this device's own export, by collection and record key. */
private fun ownHashes(
    exported: Map<String, CollectionWire>,
    keys: Map<String, Set<String>>,
): Map<String, Map<String, String>> =
    exported.mapValues { (collection, wire) ->
        val wanted = keys[collection].orEmpty()
        wire.lines
            .mapNotNull { line ->
                val record = Json.parseToJsonElement(line).jsonObject
                recordKey(collection, record)?.takeIf { it in wanted }?.let { it to recordHash(collection, record, line) }
            }.toMap()
    }
