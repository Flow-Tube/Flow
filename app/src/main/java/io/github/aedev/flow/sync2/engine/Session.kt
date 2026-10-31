package io.github.aedev.flow.sync2.engine

import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.protocol.CollectionWire
import io.github.aedev.flow.sync.protocol.SyncSerialization
import io.github.aedev.flow.sync2.changes.IncomingBatch
import io.github.aedev.flow.sync2.changes.markSent
import io.github.aedev.flow.sync2.changes.outgoingBatches
import io.github.aedev.flow.sync2.changes.parseBatch
import io.github.aedev.flow.sync2.crypto.MemberSecrets
import io.github.aedev.flow.sync2.crypto.ProtocolError
import io.github.aedev.flow.sync2.crypto.ProtocolException
import io.github.aedev.flow.sync2.crypto.checkEpochKey
import io.github.aedev.flow.sync2.crypto.decodePayload
import io.github.aedev.flow.sync2.crypto.encodePayload
import io.github.aedev.flow.sync2.crypto.openChain
import io.github.aedev.flow.sync2.crypto.purposeKey
import io.github.aedev.flow.sync2.crypto.reject
import io.github.aedev.flow.sync2.crypto.sha256
import io.github.aedev.flow.sync2.crypto.unwrapEpochKey
import io.github.aedev.flow.sync2.data.GroupRecord
import io.github.aedev.flow.sync2.data.LinkReadRow
import io.github.aedev.flow.sync2.data.LinkedDatabase
import io.github.aedev.flow.sync2.data.StreamRow
import io.github.aedev.flow.sync2.data.addEntries
import io.github.aedev.flow.sync2.data.replaceRoster
import io.github.aedev.flow.sync2.data.toEntries
import io.github.aedev.flow.sync2.roster.Entry
import io.github.aedev.flow.sync2.roster.RosterState
import io.github.aedev.flow.sync2.roster.applyEntry
import io.github.aedev.flow.sync2.roster.entryHash
import io.github.aedev.flow.sync2.roster.replayRoster
import io.github.aedev.flow.sync2.store.ChangeList
import io.github.aedev.flow.sync2.store.Header
import io.github.aedev.flow.sync2.store.ObjectType
import io.github.aedev.flow.sync2.store.ParsedObject
import io.github.aedev.flow.sync2.store.StorePaths
import io.github.aedev.flow.sync2.store.StreamPosition
import io.github.aedev.flow.sync2.store.ZERO_HASH
import io.github.aedev.flow.sync2.store.nextInStream
import io.github.aedev.flow.sync2.store.parseObject
import io.github.aedev.flow.sync2.store.sealObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.TreeMap

/** What a log object carries, once checked and opened. */
internal sealed interface Content {
    class Batch(
        val batch: IncomingBatch,
    ) : Content

    class Snapshot(
        val manifest: Manifest,
    ) : Content

    class Ack(
        val vv: Map<String, Long>,
    ) : Content
}

private class ReadObject(
    val position: StreamPosition,
    val content: Content,
)

/** Batches read from other devices, not applied yet, and how far reading got. */
class Pulled internal constructor(
    val batches: Map<String, List<IncomingBatch>>,
    internal val positions: Map<String, StreamPosition>,
    internal val cursor: Long,
    /** Batches and snapshot manifests read; acks alone don't call for an ack. */
    internal val consumed: Int,
    /** An object is missing, so reading stopped before the end of the logs. */
    internal val stalled: Boolean,
    internal val acks: Map<String, Map<String, Long>>,
    internal val manifests: List<Logged>,
    internal val links: Set<String?>,
    /** Where reading each link apart from the others stands, and the links that reached where reading of every link stood. */
    internal val reads: List<LinkReadRow>,
    internal val readsDone: List<LinkReadRow>,
)

/**
 * One sync with a store: verify the roster, push this device's changes, pull everyone else's. It
 * holds no secrets beyond the session: epoch keys are unwrapped from the store each time.
 */
class Session private constructor(
    internal val db: LinkedDatabase,
    internal val store: ChangeList,
    internal val device: MemberSecrets,
    record: GroupRecord,
    internal val group: ByteArray,
    val state: RosterState,
    internal val entries: List<Entry>,
) {
    internal val me = SyncBytes.toHex(device.did)
    private val signingKeys = HashMap<String, ByteArray>()
    private val commitments = HashMap<Int, ByteArray>()
    private val keys = TreeMap<Int, ByteArray>()

    var record = record
        private set

    internal fun currentKey(): ByteArray = keys.getValue(state.epoch)

    init {
        for (entry in entries) {
            entry.add?.forEach { signingKeys[it.did] = SyncBytes.b64urlDecode(it.sig) }
            entry.epoch?.let { commitments[it.n] = SyncBytes.b64urlDecode(it.kc) }
        }
    }

    companion object {
        /** Brings the roster up to date and unwraps the current epoch key. */
        suspend fun open(
            db: LinkedDatabase,
            store: ChangeList,
            device: MemberSecrets,
        ): Session {
            val groups = db.groups()
            val record = groups.group() ?: throw LinkedException.NotLinked()
            val entries = groups.roster().toEntries().toMutableList()
            var state = replayRoster(entries)
            val chain = store.rosterAfter(-1)
            // A store that resolved a fork the other way holds a chain this device's copy isn't a prefix of: take the winner.
            if (chain.size >= entries.size && chain.take(entries.size).map(::entryHash) != entries.map(::entryHash)) {
                groups.replaceRoster(chain)
                entries.clear()
                state = replayRoster(chain).also { entries += chain }
            } else {
                val fresh = chain.drop(entries.size)
                for (entry in fresh) state = applyEntry(state, entry)
                groups.addEntries(fresh)
                entries += fresh
            }
            if (SyncBytes.toHex(device.did) !in state.members) throw LinkedException.Removed()
            return Session(db, store, device, record, SyncBytes.fromHex(record.groupId), state, entries).apply { unwrapCurrent() }
        }
    }

    private suspend fun unwrapCurrent() {
        val epoch = state.epoch
        val wrap = store.key(epoch, me) ?: reject(ProtocolError.KEY_COMMITMENT)
        val key = unwrapEpochKey(device.kemSecret, group, epoch, device.did, wrap)
        checkEpochKey(key, group, epoch, commitments[epoch] ?: reject(ProtocolError.KEY_COMMITMENT))
        keys[epoch] = key
    }

    /** The key of [epoch], walking the history chain back from the oldest key this device holds. */
    internal suspend fun epochKey(epoch: Int): ByteArray {
        while (epoch !in keys) {
            val (known, key) = keys.firstEntry() ?: reject(ProtocolError.KEY_COMMITMENT)
            if (known <= epoch) reject(ProtocolError.KEY_COMMITMENT)
            val chain = store.key(known, "chain") ?: reject(ProtocolError.KEY_COMMITMENT)
            val previous = openChain(key, group, known, chain)
            checkEpochKey(previous, group, known - 1, commitments[known - 1] ?: reject(ProtocolError.KEY_COMMITMENT))
            keys[known - 1] = previous
        }
        return keys.getValue(epoch)
    }

    internal suspend fun write(
        type: ObjectType,
        content: ByteArray,
    ) {
        val seq = record.ownSeq + 1
        val epoch = state.epoch
        val header = Header(type, group, epoch, device.did, seq, SyncBytes.fromHex(record.ownPrev))
        val key = purposeKey(keys.getValue(epoch), group, epoch, type.purpose)
        val sealed = sealObject(header, key, encodePayload(content), device.sigSeed)
        store.putObject(StorePaths.log(me, seq), sealed)
        record = record.copy(ownSeq = seq, ownPrev = SyncBytes.toHex(sha256(sealed)))
        db.groups().setOwn(record.ownSeq, record.ownPrev)
    }

    /** Uploads what changed locally in [link] since the last upload; returns how many batches went out. */
    suspend fun push(
        exported: Map<String, CollectionWire>,
        link: String,
    ): Int {
        val outgoing = outgoingBatches(db.sent(), exported, state.head, link, System.currentTimeMillis())
        for (batch in outgoing.batches) write(ObjectType.CHANGES, batch.content)
        markSent(db.sent(), outgoing)
        return outgoing.batches.size
    }

    /**
     * Reads other devices' new objects in stream order, checking each one, and keeps the batches of [links]
     * (`null` is the `profiles` collection). Nothing is applied: the caller applies or keeps [Pulled.batches] and
     * then calls [finish].
     */
    suspend fun pull(links: Set<String?>): Pulled = read(links)

    /**
     * The links that change batches and snapshot manifests name for profile data, which a restored device chooses
     * from (spec/protocol.md, Recovery), or null while the logs can't be read to the end yet.
     */
    suspend fun namedLinks(): Set<String>? {
        val read = read(links = null)
        return if (read.stalled) null else read.links.filterNotNull().toSet()
    }

    /**
     * Starts reading [link], linked after this device read past the beginning of the logs (spec/protocol.md,
     * Reading a link from its snapshot). Returns the records of the link's newest snapshot this device knows, by
     * writer, and from then on each pull also reads the link's earlier batches; null when that snapshot can't be
     * read yet.
     */
    suspend fun startReading(link: String): Map<String, List<IncomingBatch>>? {
        val positions = db.groups().streams()
        val newest =
            db
                .collection()
                .manifests()
                .map { Logged(it.did, it.seq, parseManifest(it.manifest.toByteArray())) }
                .filter { it.manifest.link == link }
                .maxByOrNull { it.manifest.merged() }
        val tips = newest?.manifest?.vv.orEmpty()
        val records =
            newest?.let {
                val parsed = verified(store.getObject(StorePaths.log(it.did, it.seq)) ?: return null, it.did, it.seq)
                mapOf(it.did to snapshotBatches(it.did, parsed.header.epoch, it.manifest))
            } ?: emptyMap()
        db.profiles().saveReads(
            positions
                .filter { it.did != me }
                .map {
                    tips[it.did]?.let { tip -> LinkReadRow(link, it.did, tip.seq, tip.hash) }
                        ?: LinkReadRow(link, it.did, 0, SyncBytes.toHex(ZERO_HASH))
                }.filter { read -> read.seq < positions.first { it.did == read.did }.seq },
        )
        return records
    }

    /** Batches of [links] only, or every batch when [links] is null. */
    private suspend fun read(links: Set<String?>?): Pulled {
        fun wanted(link: String?) = links == null || link in links
        val positions = db.groups().streams().associateTo(HashMap()) { it.did to StreamPosition(it.seq, SyncBytes.fromHex(it.hash)) }
        val batches = LinkedHashMap<String, MutableList<IncomingBatch>>()
        var cursor = record.cursor
        var consumed = 0
        var stalled = false
        val acks = HashMap<String, Map<String, Long>>()
        val manifests = ArrayList<Logged>()
        val named = HashSet<String?>()
        val (reads, readsDone) = catchUp(db.profiles().reads().filter { wanted(it.link) }, positions, batches)
        var floors = emptyMap<Pair<String?, String>, Long>()
        if (positions.isEmpty() && cursor == 0L) {
            bootstrap(links)?.let { start ->
                start.batches.forEach { (writer, records) -> batches.getOrPut(writer) { ArrayList() } += records }
                positions += start.positions
                floors = start.floors
                named += start.floors.keys.map { it.first }
            }
        }
        while (true) {
            val (changes, next) = store.changes(cursor)
            if (changes.isEmpty()) break
            // Objects of one sender may be listed out of order: retry while a pass makes progress.
            var waiting =
                changes
                    .mapNotNull { change ->
                        StorePaths.parseLog(change.path)?.let { (did, seq) -> Triple(did, seq, change.path) }
                    }.filter { (did, seq) ->
                        did != me && (positions[did]?.seq ?: 0) < seq
                    }
            while (waiting.isNotEmpty()) {
                val still =
                    waiting.filter { (did, seq, path) ->
                        val read = readObject(path, did, seq, positions[did]) ?: return@filter true
                        positions[did] = read.position
                        when (val content = read.content) {
                            is Content.Ack -> {
                                acks[did] = content.vv
                            }

                            is Content.Snapshot -> {
                                manifests += Logged(did, seq, content.manifest)
                                named += content.manifest.link
                            }

                            is Content.Batch -> {
                                val link = content.batch.link
                                named += link
                                // A snapshot this device started from may already include it.
                                if (wanted(link) && seq > (floors[link to did] ?: 0)) batches.getOrPut(did) { ArrayList() } += content.batch
                            }
                        }
                        if (read.content !is Content.Ack) consumed++
                        false
                    }
                if (still.size == waiting.size) break
                waiting = still
            }
            // A missing object means the store hasn't caught up: read this page again next time.
            if (waiting.isNotEmpty()) {
                stalled = true
                break
            }
            cursor = next
        }
        return Pulled(batches, positions, cursor, consumed, stalled, acks, manifests, named, reads, readsDone)
    }

    /**
     * Reads each of [rows]' links on its own, from where it stands up to [positions], where reading of every link
     * stands; returns the rows still short of it, and the rows that reached it.
     */
    private suspend fun catchUp(
        rows: List<LinkReadRow>,
        positions: Map<String, StreamPosition>,
        batches: MutableMap<String, MutableList<IncomingBatch>>,
    ): Pair<List<LinkReadRow>, List<LinkReadRow>> {
        val reached =
            rows.map { row ->
                var at = StreamPosition(row.seq, SyncBytes.fromHex(row.hash))
                val end = positions[row.did]?.seq ?: 0
                while (at.seq < end) {
                    val seq = at.seq + 1
                    val read = readObject(StorePaths.log(row.did, seq), row.did, seq, at) ?: break
                    at = read.position
                    val batch = (read.content as? Content.Batch)?.batch
                    if (batch?.link == row.link) batches.getOrPut(row.did) { ArrayList() } += batch
                }
                row.copy(seq = at.seq, hash = SyncBytes.toHex(at.hash)) to (at.seq >= end)
            }
        return reached.filterNot { it.second }.map { it.first } to reached.filter { it.second }.map { it.first }
    }

    /** One log object, checked in the order of spec/store.md; `null` while an earlier one of this sender is missing. */
    private suspend fun readObject(
        path: String,
        did: String,
        seq: Long,
        last: StreamPosition?,
    ): ReadObject? {
        val parsed = verified(store.getObject(path) ?: return null, did, seq)
        val position =
            try {
                nextInStream(last, parsed)
            } catch (e: ProtocolException) {
                if (e.error == ProtocolError.GAP) return null
                throw e
            }
        return ReadObject(position, openContent(parsed))
    }

    /** Steps 1 to 3 of reading an envelope: structure, sender, signature. The caller checks its place in the stream. */
    internal fun verified(
        bytes: ByteArray,
        did: String,
        seq: Long,
    ): ParsedObject {
        val parsed = parseObject(bytes, group)
        parsed.verify(signingKeys[did] ?: reject(ProtocolError.SENDER))
        if (SyncBytes.toHex(parsed.header.sender) != did || parsed.header.seq != seq) reject(ProtocolError.REPLAY)
        return parsed
    }

    internal suspend fun openContent(parsed: ParsedObject): Content {
        val epoch = parsed.header.epoch
        val content = decodePayload(parsed.open(purposeKey(epochKey(epoch), group, epoch, parsed.header.type.purpose)))
        return when (parsed.header.type) {
            ObjectType.CHANGES -> Content.Batch(parseBatch(content))
            ObjectType.SNAPSHOT -> Content.Snapshot(parseManifest(content))
            ObjectType.ACK -> Content.Ack(parseAck(content))
            ObjectType.LIVE -> reject(ProtocolError.SUITE)
        }
    }

    /** Saves how far reading got, after the caller applied the batches, and acknowledges it. */
    suspend fun finish(pulled: Pulled) {
        db.groups().saveStreams(pulled.positions.map { (did, p) -> StreamRow(did, p.seq, SyncBytes.toHex(p.hash)) }, pulled.cursor)
        db.profiles().updateReads(pulled.reads, pulled.readsDone)
        record = record.copy(cursor = pulled.cursor)
        recordCollection(pulled)
        if (pulled.consumed > 0) {
            val vv = JsonObject(pulled.positions.toSortedMap().mapValues { (_, p) -> JsonPrimitive(p.seq) })
            write(ObjectType.ACK, SyncSerialization.canonicalJson(JsonObject(mapOf("vv" to vv))).toByteArray())
        }
    }
}
