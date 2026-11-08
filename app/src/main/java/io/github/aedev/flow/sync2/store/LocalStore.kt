package io.github.aedev.flow.sync2.store

import io.github.aedev.flow.sync2.data.HeldDao
import io.github.aedev.flow.sync2.data.HeldRow
import io.github.aedev.flow.sync2.roster.Entry
import io.github.aedev.flow.sync2.roster.ForkResolution
import io.github.aedev.flow.sync2.roster.ValidEntries
import io.github.aedev.flow.sync2.roster.canonicalEntry
import io.github.aedev.flow.sync2.roster.entryHash
import io.github.aedev.flow.sync2.roster.parseEntry
import io.github.aedev.flow.sync2.roster.resolveForks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

private const val PAGE = 500

/**
 * What this device holds of its group, in the store layout (spec/store.md): every roster entry it has
 * seen, fork candidates included, the wraps and chain boxes, and the log objects and blobs. Sessions
 * read it; the server, if any, and LAN peers fill it.
 */
class LocalStore(
    private val root: File,
    private val held: HeldDao,
) : ChangeList {
    private fun file(path: String) = File(root, path)

    private fun read(path: String): ByteArray? = file(path).takeIf { it.isFile }?.readBytes()

    /** Writes a file once; an existing one is kept, as no store overwrites. */
    private fun write(
        path: String,
        bytes: ByteArray,
    ): Boolean {
        val target = file(path)
        if (target.exists()) return false
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeBytes(bytes)
        return temp.renameTo(target)
    }

    /** Every roster entry held with a `seq` above [after], fork candidates included. */
    suspend fun entries(after: Long = -1): List<Entry> =
        withContext(Dispatchers.IO) {
            file("roster")
                .listFiles()
                .orEmpty()
                .filter { it.name.endsWith(".r") }
                .mapNotNull { runCatching { parseEntry(Json.parseToJsonElement(it.readText())) }.getOrNull() }
                .filter { it.seq > after }
                .sortedBy { it.seq }
        }

    /** What the rule makes of what this device holds, within the group it has verified. */
    suspend fun resolved(): ForkResolution {
        val genesis = held.genesis()?.let { parseEntry(Json.parseToJsonElement(it)) }
        return resolveForks(genesis?.group, entries(), held.rosterPins().toSet(), genesis?.let(::entryHash))
    }

    /** What decides whether a new entry counts, given what this device holds. */
    internal suspend fun validEntries(): ValidEntries {
        val genesis = held.genesis()?.let { parseEntry(Json.parseToJsonElement(it)) }
        val valid = ValidEntries(genesis?.group, genesis?.let(::entryHash))
        entries().forEach(valid::admit)
        return valid
    }

    suspend fun addEntries(entries: List<Entry>) =
        withContext(Dispatchers.IO) {
            entries.forEach { write(StorePaths.roster(it.seq, entryHash(it)), canonicalEntry(it).toByteArray()) }
        }

    suspend fun putKey(
        epoch: Int,
        kc: String,
        name: String,
        bytes: ByteArray,
    ) {
        withContext(Dispatchers.IO) { write(StorePaths.key(epoch, kc, name), bytes) }
    }

    override suspend fun forgetKey(
        epoch: Int,
        kc: String,
        name: String,
    ) {
        withContext(Dispatchers.IO) { file(StorePaths.key(epoch, kc, name)).delete() }
    }

    /** Holds an entry with its wraps and chain box, wraps first (spec/store.md, Writing rules). */
    suspend fun hold(
        entry: Entry,
        below: List<Entry>,
        wraps: Map<String, ByteArray>,
        chain: ByteArray?,
    ) {
        val (epoch, kc) = wrapsEpoch(entry, below)
        wraps.forEach { (did, wrap) -> putKey(epoch, kc, did, wrap) }
        chain?.let { putKey(epoch, kc, "chain", it) }
        addEntries(listOf(entry))
    }

    override suspend fun rosterAfter(after: Long): List<Entry> = resolved().chain.filter { it.seq > after }

    override suspend fun rosterView(): List<Entry> = entries()

    override val linear: Boolean get() = false

    override val heldHere: Boolean get() = true

    override suspend fun appendRoster(
        head: String,
        entry: Entry,
        wraps: Map<String, String>,
        chain: String?,
    ): Appended {
        val current = resolved().chain
        val currentHead = current.lastOrNull()?.let(::entryHash).orEmpty()
        if (currentHead != head) return Appended.Conflict(currentHead)
        hold(entry, current, wraps.mapValues { (_, wrap) -> wrapBytes(wrap) }, chain?.let(::wrapBytes))
        return Appended.Done
    }

    override suspend fun key(
        epoch: Int,
        kc: String,
        name: String,
    ): ByteArray? = withContext(Dispatchers.IO) { read(StorePaths.key(epoch, kc, name)) }

    override suspend fun putObject(
        path: String,
        bytes: ByteArray,
    ) {
        require(StorePaths.isObject(path)) { "Not an object path" }
        withContext(Dispatchers.IO) { write(path, bytes) }
        held.add(HeldRow(path = path, size = bytes.size.toLong()))
    }

    override suspend fun getObject(path: String): ByteArray? =
        if (StorePaths.isObject(path)) withContext(Dispatchers.IO) { read(path) } else null

    override suspend fun deleteObject(path: String) {
        if (!StorePaths.isObject(path)) return
        withContext(Dispatchers.IO) { file(path).delete() }
        held.remove(path)
    }

    override suspend fun changes(cursor: Long): Pair<List<ChangedObject>, Long> {
        val rows = held.after(cursor, PAGE)
        return rows.map { ChangedObject(it.path, it.size) } to (rows.lastOrNull()?.cursor ?: cursor)
    }

    /** Forgets everything held, when this device leaves its group; the index goes with the database's tables. */
    suspend fun clear() {
        withContext(Dispatchers.IO) { root.deleteRecursively() }
    }

    /** The highest log `seq` held of each sender. */
    suspend fun logTips(): Map<String, Long> =
        held
            .paths()
            .mapNotNull(StorePaths::parseLog)
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, seqs) -> seqs.max() }

    /** What a LAN peer asked for with [vv]: the log objects held above it, and the blobs held. */
    suspend fun list(vv: Map<String, Long>): Pair<List<String>, List<String>> {
        val paths = held.paths()
        val logs = paths.filter { path -> StorePaths.parseLog(path)?.let { (did, seq) -> seq > (vv[did] ?: 0) } == true }
        return logs to paths.filter { StorePaths.parseLog(it) == null }
    }
}
