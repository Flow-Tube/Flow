package io.github.aedev.flow.sync2.engine

import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.protocol.CollectionWire
import io.github.aedev.flow.sync.protocol.SyncSerialization
import io.github.aedev.flow.sync2.changes.batchContent
import io.github.aedev.flow.sync2.crypto.ProtocolError
import io.github.aedev.flow.sync2.crypto.encodePayload
import io.github.aedev.flow.sync2.crypto.purposeKey
import io.github.aedev.flow.sync2.crypto.reject
import io.github.aedev.flow.sync2.crypto.sealBlob
import io.github.aedev.flow.sync2.crypto.sha256
import io.github.aedev.flow.sync2.data.SnapshotDao
import io.github.aedev.flow.sync2.store.ObjectType
import io.github.aedev.flow.sync2.store.StorePaths
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private const val MERGED_LIMIT = 1024 * 1024L
private const val MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L

/** spec/store.md asks for at most 2 MiB of payload per blob. */
private const val BLOB_CONTENT_LIMIT = 2 * 1024 * 1024

@Serializable
data class Part(
    val blob: String,
    val collection: String,
    val size: Long,
)

/** The last object of a log that a snapshot includes, so reading can go on from it. */
@Serializable
data class Tip(
    val hash: String,
    val seq: Long,
)

@Serializable
data class Manifest(
    val head: String,
    val link: String?,
    val parts: List<Part>,
    val vv: Map<String, Tip>,
)

internal fun parseManifest(content: ByteArray): Manifest =
    try {
        Json.decodeFromString(Manifest.serializer(), content.toString(Charsets.UTF_8))
    } catch (_: SerializationException) {
        reject(ProtocolError.PAYLOAD)
    }

suspend fun SnapshotDao.isDue(
    link: String,
    nowMs: Long,
): Boolean = forLink(link)?.let { it.mergedBytes > MERGED_LIMIT || nowMs - it.writtenMs >= MAX_AGE_MS } ?: false

/** Splits records into blob-sized groups. */
internal fun chunks(lines: List<String>): List<List<String>> {
    val chunks = ArrayList<MutableList<String>>()
    var size = 0
    for (line in lines) {
        val bytes = line.toByteArray().size
        if (chunks.isEmpty() || size + bytes >= BLOB_CONTENT_LIMIT) {
            chunks += ArrayList<String>()
            size = 0
        }
        chunks.last() += line
        size += bytes + 1
    }
    return chunks
}

/**
 * Writes [exported], the whole state of [link], as blobs and then a manifest naming the last object of every log
 * merged into it. Only the active profile's link qualifies, once nothing kept for it is left to apply.
 */
suspend fun Session.writeSnapshot(
    exported: Map<String, CollectionWire>,
    link: String,
) {
    val epoch = state.epoch
    val key = purposeKey(currentKey(), group, epoch, ObjectType.SNAPSHOT.purpose)
    val parts =
        exported.flatMap { (collection, wire) ->
            chunks(wire.lines).map { lines ->
                val blob = sealBlob(key, group, epoch, device.did, encodePayload(batchContent(collection, state.head, link, lines)))
                val name = SyncBytes.toHex(sha256(blob))
                store.putObject(StorePaths.blob(me, name), blob)
                Part(name, collection, blob.size.toLong())
            }
        }
    val vv = db.groups().streams().associate { it.did to Tip(it.hash, it.seq) } + (me to Tip(record.ownPrev, record.ownSeq))
    val manifest = Manifest(state.head, link, parts, vv.toSortedMap())
    write(ObjectType.SNAPSHOT, SyncSerialization.canonicalJson(Json.encodeToJsonElement(Manifest.serializer(), manifest)).toByteArray())
    recordManifests(listOf(Logged(me, record.ownSeq, manifest)))
    db.snapshots().written(link, System.currentTimeMillis())
}
