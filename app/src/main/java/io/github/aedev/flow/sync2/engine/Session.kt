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
import io.github.aedev.flow.sync2.crypto.Purpose
import io.github.aedev.flow.sync2.crypto.checkEpochKey
import io.github.aedev.flow.sync2.crypto.decodePayload
import io.github.aedev.flow.sync2.crypto.encodePayload
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
import io.github.aedev.flow.sync2.data.serverUrl
import io.github.aedev.flow.sync2.data.toEntries
import io.github.aedev.flow.sync2.data.webDavUrl
import io.github.aedev.flow.sync2.roster.Entry
import io.github.aedev.flow.sync2.roster.Op
import io.github.aedev.flow.sync2.roster.Role
import io.github.aedev.flow.sync2.roster.RosterState
import io.github.aedev.flow.sync2.roster.Settlement
import io.github.aedev.flow.sync2.roster.applyEntry
import io.github.aedev.flow.sync2.roster.checkSender
import io.github.aedev.flow.sync2.roster.entryHash
import io.github.aedev.flow.sync2.roster.replayRoster
import io.github.aedev.flow.sync2.roster.settleRoster
import io.github.aedev.flow.sync2.store.ChangeList
import io.github.aedev.flow.sync2.store.Header
import io.github.aedev.flow.sync2.store.ObjectType
import io.github.aedev.flow.sync2.store.ParsedObject
import io.github.aedev.flow.sync2.store.StoreException
import io.github.aedev.flow.sync2.store.StoreLogin
import io.github.aedev.flow.sync2.store.StorePaths
import io.github.aedev.flow.sync2.store.StreamPosition
import io.github.aedev.flow.sync2.store.ZERO_HASH
import io.github.aedev.flow.sync2.store.cutChain
import io.github.aedev.flow.sync2.store.decodeStoreLogin
import io.github.aedev.flow.sync2.store.encodeStoreLogin
import io.github.aedev.flow.sync2.store.kcName
import io.github.aedev.flow.sync2.store.nextInStream
import io.github.aedev.flow.sync2.store.parseObject
import io.github.aedev.flow.sync2.store.sealObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

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

    /** A store login this device may take; null when the read rule says to ignore it. */
    class Login(
        val login: StoreLogin?,
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
    /** An object is missing or was discarded, so some log was read short of the end. */
    internal val stalled: Boolean,
    internal val acks: Map<String, Map<String, Long>>,
    internal val manifests: List<Logged>,
    internal val links: Set<String?>,
    /** Where reading each link apart from the others stands, and the links that reached where reading of every link stood. */
    internal val reads: List<LinkReadRow>,
    internal val readsDone: List<LinkReadRow>,
    /** The latest store login read, for the caller to keep as pending. */
    val login: StoreLogin?,
    /** Objects removed senders wrote after their logs ended, which [Session.finish] deletes. */
    internal val pastCut: List<String> = emptyList(),
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
    standing: List<Entry> = emptyList(),
) {
    internal val me = SyncBytes.toHex(device.did)
    private val commitments = HashMap<Int, ByteArray>()
    private val keys = EpochKeys(store, group, commitments)

    /** The `kc` of each entry that lost a fork and stands, by the epoch it starts. */
    private val standing = standing.mapNotNull { it.epoch }.groupBy({ it.n }, { it.kc })

    /** The keys of those entries unwrapped so far, by `kc`. */
    private val standingKeys = HashMap<String, ByteArray>()

    /** The hashes found walking down from each removed sender's cut. */
    private val cutWalks = HashMap<String, Map<Long, ByteArray>>()

    var record = record
        private set

    internal fun currentKey(): ByteArray = keys.held(state.epoch)

    /** A restore hasn't signed out the devices the user didn't choose yet, so they hold this key (spec/protocol.md, Recovery). */
    val removalPending: Boolean get() = record.restoreKeep != null

    /** The current key for sealing anything other devices will read. */
    internal fun sealingKey(): ByteArray {
        if (removalPending) throw LinkedException.RestoreUnfinished()
        return currentKey()
    }

    init {
        for (entry in entries) {
            entry.epoch?.let { commitments[it.n] = SyncBytes.b64urlDecode(it.kc) }
        }
    }

    companion object {
        /**
         * What to settle from: a store's answer, or what this device holds with what it has applied.
         * Peers on the LAN of a group on a server can only extend the applied chain: its server orders the rest.
         */
        internal suspend fun viewOf(
            store: ChangeList,
            record: GroupRecord,
            applied: List<Entry>,
        ): List<Entry> {
            val shown = store.rosterView()
            if (!store.heldHere) return shown
            if (record.serverUrl == null) return applied + shown
            val reached = applied.lastOrNull()?.let(::entryHash)?.let { hashSetOf(it) } ?: hashSetOf()
            return applied + shown.sortedBy { it.seq }.filter { it.prev in reached && reached.add(entryHash(it)) }
        }

        /** The chain this device has applied, without asking the store whether it still stands. */
        internal suspend fun onApplied(
            db: LinkedDatabase,
            store: ChangeList,
            device: MemberSecrets,
        ): Session {
            val record = db.groups().group() ?: throw LinkedException.NotLinked()
            val entries = db.groups().roster().toEntries()
            val state = replayRoster(entries)
            val session = Session(db, store, device, record, SyncBytes.fromHex(record.groupId), state, entries)
            runCatching { session.unwrapCurrent() }
            return session
        }

        /** Brings the roster up to date and unwraps the current epoch key. */
        suspend fun open(
            db: LinkedDatabase,
            store: ChangeList,
            device: MemberSecrets,
        ): Session {
            val groups = db.groups()
            val record = groups.group() ?: throw LinkedException.NotLinked()
            val applied = groups.roster().toEntries()
            replayRoster(applied)
            val pins = groups.rosterPins().toSet()
            store.usePins(pins)
            val settled = settleRoster(record.groupId, applied, viewOf(store, record, applied), pins, store.linear)
            if (settled is Settlement.Contested) throw LinkedException.Contested()
            if (settled is Settlement.Rollback) throw LinkedException.RosterRolledBack()
            if (settled !is Settlement.Apply) throw LinkedException.RosterRewritten()
            val entries = settled.chain.toMutableList()
            val state = replayRoster(entries)
            val member = SyncBytes.toHex(device.did) in state.members
            if (!settled.extendsApplied && !member) throw LinkedException.RemovalUnconfirmed()
            val session = Session(db, store, device, record, SyncBytes.fromHex(record.groupId), state, entries, settled.standing)
            // Nothing is saved until the new chain's key opens: without it the device stays where it was.
            if (member) session.unwrapCurrent()
            if (settled.extendsApplied) groups.addEntries(entries.drop(applied.size)) else groups.replaceRoster(entries)
            recoveryChange(SyncBytes.toHex(device.did), applied, entries)?.let { groups.setRecoveryNotice(it, System.currentTimeMillis()) }
            if (record.recoveryDue != session.state.recoveryDue) groups.setRecoveryDue(session.state.recoveryDue)
            if (record.webDavUrl != null) {
                val known = applied.map(::entryHash).toSet()
                entries.lastOrNull { it.op == Op.REMOVE && entryHash(it) !in known }?.epoch?.let { groups.setLoginDue(it.n) }
            }
            if (!member) throw LinkedException.Removed()
            return session
        }

        /** Like [open], but while this device lacks its wrap of the settled chain's epoch it stays on the chain it had: a LAN session is how it gets that wrap. */
        suspend fun openForLan(
            db: LinkedDatabase,
            store: ChangeList,
            device: MemberSecrets,
        ): Session =
            try {
                open(db, store, device)
            } catch (e: ProtocolException) {
                if (e.error != ProtocolError.KEY_COMMITMENT) throw e
                onApplied(db, store, device)
            }
    }

    private suspend fun unwrapCurrent() {
        keys[state.epoch] = unwrapWrap(state.epoch, commitments[state.epoch] ?: reject(ProtocolError.KEY_COMMITMENT))
    }

    /** This device's key of the entry that starts [epoch] with [commitment]. */
    private suspend fun unwrapWrap(
        epoch: Int,
        commitment: ByteArray,
    ): ByteArray {
        val kc = kcName(commitment)
        val wrap = store.key(epoch, kc, me) ?: reject(ProtocolError.KEY_COMMITMENT)
        return keys.forgettingIfBad(epoch, kc, me) {
            unwrapEpochKey(device.kemSecret, group, epoch, device.did, wrap).also { checkEpochKey(it, group, epoch, commitment) }
        }
    }

    /** The key of [epoch], walking the history chain back from the oldest key this device holds. */
    internal suspend fun epochKey(epoch: Int): ByteArray = keys.key(epoch)

    /**
     * Opens something sealed under [epoch] with the chain's key, or else with the key of an entry that
     * lost that epoch and stands (spec/store.md, Reading an envelope).
     */
    internal suspend fun <T> openUnder(
        epoch: Int,
        open: (ByteArray) -> T,
    ): T {
        val failure =
            try {
                return open(epochKey(epoch))
            } catch (e: ProtocolException) {
                e
            }
        for (kc in standing[epoch].orEmpty()) {
            try {
                return open(standingKeys.getOrPut(kc) { unwrapWrap(epoch, SyncBytes.b64urlDecode(kc)) })
            } catch (_: ProtocolException) {
                continue
            }
        }
        throw failure
    }

    internal suspend fun write(
        type: ObjectType,
        content: ByteArray,
    ) {
        if (state.recoveryDue) throw LinkedException.RecoveryDue()
        val seq = record.ownSeq + 1
        val epoch = state.epoch
        val header = Header(type, group, epoch, device.did, seq, SyncBytes.fromHex(record.ownPrev))
        val key = purposeKey(sealingKey(), group, epoch, type.purpose)
        val sealed = sealObject(header, key, encodePayload(content), device.sigSeed)
        store.putObject(StorePaths.log(me, seq), sealed)
        record = record.copy(ownSeq = seq, ownPrev = SyncBytes.toHex(sha256(sealed)))
        db.groups().setOwn(record.ownSeq, record.ownPrev)
    }

    /** Uploads what changed locally in [link] (null for `profiles`) since the last upload; returns how many batches went out. */
    suspend fun push(
        exported: Map<String, CollectionWire>,
        link: String?,
    ): Int {
        if (state.recoveryDue) return 0
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
            try {
                newest?.let {
                    val parsed = verified(store.getObject(StorePaths.log(it.did, it.seq)) ?: return null, it.did, it.seq)
                    mapOf(it.did to snapshotBatches(it.did, parsed.header.epoch, it.manifest))
                } ?: emptyMap()
            } catch (_: ProtocolException) {
                return null
            }
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

    /** How far every other device's log can be read now, with nothing saved. */
    internal suspend fun readablePositions(): Map<String, StreamPosition> = read(links = null).positions

    /** Batches of [links] only, or every batch when [links] is null. */
    private suspend fun read(links: Set<String?>?): Pulled {
        fun wanted(link: String?) = links == null || link in links
        val positions = db.groups().streams().associateTo(HashMap()) { it.did to StreamPosition(it.seq, SyncBytes.fromHex(it.hash)) }
        val batches = LinkedHashMap<String, MutableList<IncomingBatch>>()
        var cursor = record.cursor
        var consumed = 0
        // Where the first page that left an object unread starts; reading goes on for the other senders.
        var held: Long? = null
        // The lowest seq of each sender left unread; nothing after it is read this time.
        val stopped = HashMap<String, Long>()
        val acks = HashMap<String, Map<String, Long>>()
        val pastCut = ArrayList<String>()
        val manifests = ArrayList<Logged>()
        val named = HashSet<String?>()
        var login: StoreLogin? = null
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
            val before = positions.mapValues { it.value.seq }
            // Objects of one sender may be listed out of order: retry while a pass makes progress.
            val listed =
                changes
                    .mapNotNull { change ->
                        StorePaths.parseLog(change.path)?.let { (did, seq) -> Triple(did, seq, change.path) }
                    }.filter { (did, seq) -> did != me && (positions[did]?.seq ?: 0) < seq }
            val (ended, reading) = listed.partition { (did, seq) -> pastItsCut(did, seq) }
            pastCut += ended.map { it.third }
            var waiting = reading.filter { (did, seq) -> seq < (stopped[did] ?: Long.MAX_VALUE) }
            while (waiting.isNotEmpty()) {
                val still =
                    waiting.filter { (did, seq, path) ->
                        val read = readObject(path, did, seq, positions[did]) ?: return@filter true
                        positions[did] = read.position
                        when (val content = read.content) {
                            is Content.Ack -> {
                                acks[did] = content.vv
                            }

                            is Content.Login -> {
                                login = content.login ?: login
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
            // A missing or discarded object stops only its sender's log: read this page again next time.
            if (waiting.isNotEmpty()) {
                held = held ?: cursor
                waiting.forEach { (did, seq) -> stopped.merge(did, seq, ::minOf) }
            }
            // A page that moves neither the cursor nor any log would be handed out forever.
            if (next <= cursor && positions.all { (did, at) -> before[did] == at.seq }) break
            cursor = next
        }
        return Pulled(batches, positions, held ?: cursor, consumed, held != null, acks, manifests, named, reads, readsDone, login, pastCut)
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

    /**
     * One log object, checked in the order of spec/store.md; `null` while an earlier one of this sender is missing,
     * or when this one is discarded: either stops reading this sender's log here (spec/store.md, Untrusted input).
     */
    private suspend fun readObject(
        path: String,
        did: String,
        seq: Long,
        last: StreamPosition?,
    ): ReadObject? {
        val bytes = store.getObject(path) ?: return null
        return try {
            val parsed = verified(bytes, did, seq)
            val position = nextInStream(last, parsed)
            checkCutChain(did, parsed, last)
            ReadObject(position, openContent(parsed))
        } catch (_: ProtocolException) {
            null
        }
    }

    /** Steps 1 to 3 of reading an envelope: structure, sender, signature. The caller checks its place in the stream. */
    internal fun verified(
        bytes: ByteArray,
        did: String,
        seq: Long,
    ): ParsedObject {
        val parsed = parseObject(bytes, group)
        parsed.verify(checkSender(state.senders, did, parsed.header.epoch, seq).sig)
        if (SyncBytes.toHex(parsed.header.sender) != did || parsed.header.seq != seq) reject(ProtocolError.REPLAY)
        return parsed
    }

    /** An object a removed sender wrote after its log ended, which never gets read, so it isn't missing either. */
    private fun pastItsCut(
        did: String,
        seq: Long,
    ): Boolean {
        val sender = state.senders[did] ?: return false
        return sender.until != null && seq > (sender.cut?.seq ?: 0)
    }

    /**
     * Checks a removed sender's chain from where this device has read, down from the hash its removal names, before
     * anything of it is applied (spec/store.md, A removed sender's chain).
     */
    private suspend fun checkCutChain(
        did: String,
        parsed: ParsedObject,
        last: StreamPosition?,
    ) {
        if (state.senders[did]?.until == null) return
        val seq = parsed.header.seq
        if (cutWalks[did]?.containsKey(seq) != true) cutWalks[did] = walkCut(did, last?.seq ?: 0, last?.hash ?: ZERO_HASH)
        if (!MessageDigest.isEqual(cutWalks[did]?.get(seq), parsed.hash)) reject(ProtocolError.PREV)
    }

    /** The hashes of a removed sender's log from its cut down to [from]. */
    internal suspend fun walkCut(
        did: String,
        from: Long,
        hash: ByteArray?,
    ): Map<Long, ByteArray> {
        val cut = state.senders[did]?.cut ?: reject(ProtocolError.SENDER)
        return cutChain(cut.seq, SyncBytes.fromHex(cut.hash), from, hash, group) { store.getObject(StorePaths.log(did, it)) }
    }

    /**
     * A store login, under its own rule (spec/store.md, Store logins): only under the chain's key of the
     * epoch in force, from a current device, and never a reason to stop reading a log.
     */
    private fun loginIn(parsed: ParsedObject): StoreLogin? {
        val epoch = parsed.header.epoch
        val sender = state.members[SyncBytes.toHex(parsed.header.sender)] ?: return null
        if (record.webDavUrl == null || epoch != state.epoch || sender.role != Role.DEVICE) return null
        return try {
            decodeStoreLogin(decodePayload(parsed.open(purposeKey(currentKey(), group, epoch, Purpose.STORE))))
        } catch (_: ProtocolException) {
            null
        }
    }

    /** Writes [login] to this device's log (spec/protocol.md, Replacing a store's login). The session was just opened, so the roster was re-read and isn't contested. */
    suspend fun writeLogin(login: StoreLogin) {
        write(ObjectType.LOGIN, encodeStoreLogin(login))
        db.groups().setLoginSent(record.ownSeq)
        db.groups().noteLogin(state.epoch)
    }

    internal suspend fun openContent(parsed: ParsedObject): Content {
        if (parsed.header.type == ObjectType.LOGIN) return Content.Login(loginIn(parsed))
        val epoch = parsed.header.epoch
        val content = decodePayload(openUnder(epoch) { parsed.open(purposeKey(it, group, epoch, parsed.header.type.purpose)) })
        return when (parsed.header.type) {
            ObjectType.CHANGES -> Content.Batch(parseBatch(content))
            ObjectType.SNAPSHOT -> Content.Snapshot(parseManifest(content))
            ObjectType.ACK -> Content.Ack(parseAck(content))
            ObjectType.LIVE, ObjectType.LOGIN -> reject(ProtocolError.SUITE)
        }
    }

    /** Saves how far reading got, after the caller applied the batches, and acknowledges it. */
    suspend fun finish(pulled: Pulled) {
        if (pulled.login != null) db.groups().noteLogin(state.epoch)
        db.groups().saveStreams(pulled.positions.map { (did, p) -> StreamRow(did, p.seq, SyncBytes.toHex(p.hash)) }, pulled.cursor)
        db.profiles().updateReads(pulled.reads, pulled.readsDone)
        record = record.copy(cursor = pulled.cursor)
        recordCollection(pulled)
        try {
            pulled.pastCut.forEach { store.deleteObject(it) }
        } catch (_: StoreException) {
            // Best effort: a new reader's listing finds them again.
        }
        if (pulled.consumed > 0 && !state.recoveryDue) {
            val vv = JsonObject(pulled.positions.toSortedMap().mapValues { (_, p) -> JsonPrimitive(p.seq) })
            write(ObjectType.ACK, SyncSerialization.canonicalJson(JsonObject(mapOf("vv" to vv))).toByteArray())
        }
    }
}
