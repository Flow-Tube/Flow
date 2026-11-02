package io.github.aedev.flow.sync2.store

import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync2.roster.Entry
import io.github.aedev.flow.sync2.roster.canonicalEntry
import io.github.aedev.flow.sync2.roster.entryHash
import io.github.aedev.flow.sync2.roster.parseEntry
import io.github.aedev.flow.sync2.roster.resolveForks
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

private val DID = Regex("[0-9a-f]{32}")
private val LOG_NAME = Regex("([0-9a-f]{16})\\.o")

/**
 * The store layout (spec/store.md) on plain files. Roster appends aren't linearized, so competing
 * entries both stay and every device picks the same winner; [me] deletes only its own paths.
 */
class FileLayout(
    private val files: FileStore,
    private val me: String,
) : Remote {
    private suspend fun entry(path: String): Entry? =
        files.get(path)?.let { runCatching { parseEntry(Json.parseToJsonElement(it.decodeToString())) }.getOrNull() }

    private suspend fun rosterPaths(): List<String> = files.list("roster").filter { it.endsWith(".r") }.map { "roster/$it" }

    /** Every roster entry stored, fork candidates included. */
    private suspend fun entries(): List<Entry> = rosterPaths().mapNotNull { entry(it) }

    override suspend fun rosterAfter(after: Long): List<Entry> = resolveForks(entries()).filter { it.seq > after }

    /** Wraps and the chain box first, then the entry (spec/store.md, Writing rules). */
    override suspend fun appendRoster(
        head: String,
        entry: Entry,
        wraps: Map<String, String>,
        chain: String?,
    ): Appended {
        val current = resolveForks(entries())
        val currentHead = current.lastOrNull()?.let(::entryHash).orEmpty()
        if (currentHead != head) return Appended.Conflict(currentHead)
        val epoch = epochOf(entry, current)
        wraps.forEach { (did, wrap) -> files.put(StorePaths.key(epoch, did), wrapBytes(wrap)) }
        chain?.let { files.put(StorePaths.key(epoch, "chain"), wrapBytes(it)) }
        files.put(StorePaths.roster(entry.seq, entryHash(entry)), canonicalEntry(entry).toByteArray())
        return Appended.Done
    }

    override suspend fun key(
        epoch: Int,
        name: String,
    ): ByteArray? = files.get(StorePaths.key(epoch, name))

    override suspend fun putObject(
        path: String,
        bytes: ByteArray,
    ) = files.put(path, bytes)

    override suspend fun getObject(path: String): ByteArray? = files.get(path)

    override suspend fun deleteObject(path: String) {
        if (path.startsWith("dev/$me/")) files.delete(path)
    }

    /** Copies the roster entries [local] doesn't hold, fork candidates included, so it resolves forks as every device does. */
    suspend fun pullRoster(local: LocalStore) {
        val held = local.entries().map { StorePaths.roster(it.seq, entryHash(it)) }.toSet()
        local.addEntries(rosterPaths().filter { it !in held }.mapNotNull { entry(it) })
    }

    /**
     * Copies every other sender's log objects after the last one [local] holds, reading the next `seq`
     * until there is none, or a file that isn't that object (spec/store.md, WebDAV). A sender never read starts at its oldest object.
     * When the store tags folders, nothing is read while the tag stays the same.
     */
    suspend fun pullLogs(local: LocalStore) {
        val tag = files.tag("dev")
        if (tag != null && tags[files.location] == tag) return
        val tips = local.logTips()
        for (did in files.list("dev").filter { it != me && DID.matches(it) }) {
            var seq = tips[did]?.plus(1) ?: oldest(did) ?: continue
            while (true) {
                val path = StorePaths.log(did, seq)
                val bytes = files.get(path) ?: break
                // A store that answers every path, with an error page say, would otherwise never end the log.
                if (!namesLogObject(bytes, SyncBytes.fromHex(did), seq)) break
                local.putObject(path, bytes)
                seq++
            }
        }
        tag?.let { tags[files.location] = it }
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
