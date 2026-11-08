package io.github.aedev.flow.sync2.store

import io.github.aedev.flow.sync2.roster.Entry
import io.github.aedev.flow.sync2.roster.canonicalEntry
import io.github.aedev.flow.sync2.roster.entryHash
import io.github.aedev.flow.sync2.roster.parseEntry
import io.github.aedev.flow.sync2.roster.winningChain
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

private val DID = Regex("[0-9a-f]{32}")
private val LOG_NAME = Regex("([0-9a-f]{16})\\.o")

/** How many files and bytes of `roster/` one read takes. Far above a real group's: a roster holds at most 4,096 small entries. */
private const val ROSTER_FILES = 16_384
private const val ROSTER_BYTES = 64L * 1024 * 1024

/**
 * The store layout (spec/store.md) on plain files. Roster appends aren't linearized, so competing
 * entries both stay and every device picks the same winner; [me] deletes only its own paths.
 */
class FileLayout(
    private val files: FileStore,
    private val me: String,
) : Remote {
    private fun entry(bytes: ByteArray): Entry? = runCatching { parseEntry(Json.parseToJsonElement(bytes.decodeToString())) }.getOrNull()

    suspend fun rosterPaths(): List<String> =
        files
            .list("roster")
            .filter { it.endsWith(".r") }
            .map { "roster/$it" }
            .sorted()

    /** Every roster entry stored, fork candidates included. All of them stay in memory, so past the limits nothing is answered. */
    private suspend fun entries(): List<Entry> {
        val paths = rosterPaths()
        if (paths.size > ROSTER_FILES) throw StoreException.Malformed()
        var bytes = 0L
        return paths.mapNotNull { path ->
            oneItem { files.get(path) }?.let { file ->
                bytes += file.size
                if (bytes > ROSTER_BYTES) throw StoreException.Malformed()
                entry(file)
            }
        }
    }

    @Volatile
    private var pins: Set<String> = emptySet()

    override suspend fun rosterAfter(after: Long): List<Entry> = winningChain(entries(), pins).filter { it.seq > after }

    override suspend fun rosterView(): List<Entry> = entries()

    override val linear: Boolean get() = false

    override fun usePins(pins: Set<String>) {
        this.pins = pins
    }

    /** Wraps and the chain box first, then the entry (spec/store.md, Writing rules). */
    override suspend fun appendRoster(
        head: String,
        entry: Entry,
        wraps: Map<String, String>,
        chain: String?,
    ): Appended {
        val current = winningChain(entries(), pins)
        val currentHead = current.lastOrNull()?.let(::entryHash).orEmpty()
        if (currentHead != head) return Appended.Conflict(currentHead)
        val (epoch, kc) = wrapsEpoch(entry, current)
        wraps.forEach { (did, wrap) -> files.put(StorePaths.key(epoch, kc, did), wrapBytes(wrap)) }
        chain?.let { files.put(StorePaths.key(epoch, kc, "chain"), wrapBytes(it)) }
        files.put(StorePaths.roster(entry.seq, entryHash(entry)), canonicalEntry(entry).toByteArray())
        return Appended.Done
    }

    override suspend fun key(
        epoch: Int,
        kc: String,
        name: String,
    ): ByteArray? = files.get(StorePaths.key(epoch, kc, name))

    override suspend fun putObject(
        path: String,
        bytes: ByteArray,
    ) = files.put(path, bytes)

    override suspend fun getObject(path: String): ByteArray? = files.get(path)

    override suspend fun deleteObject(path: String) {
        if (path.startsWith("dev/$me/")) files.delete(path)
    }

    /**
     * Copies the roster entries [local] doesn't hold, fork candidates included, so it resolves forks as every device does.
     * One file at a time, lowest `seq` first, keeping only what counts: anything else is read again next time, up to the limits.
     */
    suspend fun pullRoster(local: LocalStore) {
        val held = local.entries().map { StorePaths.roster(it.seq, entryHash(it)) }.toSet()
        val fresh = rosterPaths().filter { it !in held }.take(ROSTER_FILES)
        if (fresh.isEmpty()) return
        val valid = local.validEntries()
        var bytes = 0L
        for (path in fresh) {
            if (bytes > ROSTER_BYTES) return
            val file = oneItem { files.get(path) } ?: continue
            bytes += file.size
            val entry = entry(file) ?: continue
            if (valid.admit(entry) != null) local.addEntries(listOf(entry))
        }
    }

    /**
     * Copies every other sender's log objects after the last one [local] holds, reading the next `seq`
     * until there is none, or a file that isn't that object (spec/store.md, WebDAV). A sender never read starts at its oldest object.
     * When the store tags folders, nothing is read while the tag stays the same. False when [budget] ended the read.
     */
    suspend fun pullLogs(
        local: LocalStore,
        budget: ReadBudget = ReadBudget(),
    ): Boolean {
        val tag = files.tag("dev")
        if (tag != null && tags[files.location] == tag) return true
        val check = ObjectCheck(me, local.resolved().chain)
        val tips = local.logTips()
        var whole = true
        for (did in files.list("dev").filter { it != me && DID.matches(it) }) {
            var seq = tips[did]?.plus(1) ?: oldest(did) ?: continue
            while (true) {
                val path = StorePaths.log(did, seq)
                val bytes =
                    try {
                        files.get(path)
                    } catch (e: StoreException) {
                        if (!e.stopsOnlyItsItem) throw e
                        whole = false
                        break
                    } ?: break
                // A store that answers every path, with an error page say, would otherwise never end the log.
                if (!check.admits(path, bytes)) {
                    whole = false
                    break
                }
                local.putObject(path, bytes)
                if (!budget.take(1)) return false
                seq++
            }
        }
        if (whole) tag?.let { tags[files.location] = it }
        return true
    }

    /** The oldest log object of [did] still stored, from its first shard only. */
    private suspend fun oldest(did: String): Long? {
        val shard = files.list("dev/$did/log").minOrNull() ?: return null
        return files
            .list("dev/$did/log/$shard")
            .mapNotNull {
                LOG_NAME
                    .matchEntire(it)
                    ?.groupValues
                    ?.get(1)
                    ?.toULong(16)
                    ?.toLong()
            }.minOrNull()
    }

    /** A snapshot blob, read when a session needs it rather than with every sync. */
    suspend fun blob(path: String): ByteArray? = if (StorePaths.isBlob(path)) files.get(path) else null

    override suspend fun openSlot(slot: String) = Unit

    override suspend fun putMessage(
        slot: String,
        name: String,
        bytes: ByteArray,
    ) = files.put(StorePaths.pair(slot, name), bytes)

    override suspend fun getMessage(
        slot: String,
        name: String,
    ): ByteArray? = files.get(StorePaths.pair(slot, name))

    override suspend fun closeSlot(slot: String) = files.delete("pair/$slot/")

    private companion object {
        /** The `dev` tag each store last showed after a full read, while the app runs. */
        val tags = ConcurrentHashMap<String, String>()
    }
}
