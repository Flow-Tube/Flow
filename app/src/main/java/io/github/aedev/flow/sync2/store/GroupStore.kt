package io.github.aedev.flow.sync2.store

import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync2.roster.Entry
import io.github.aedev.flow.sync2.roster.replayRoster

/**
 * The store a session works on: what this device holds, with the group's shared store, when it has
 * one: a Flow server, or files through [FileLayout]. Objects reach it through [upload].
 */
class GroupStore(
    val local: LocalStore,
    val server: Remote?,
) : ChangeList {
    override suspend fun rosterAfter(after: Long): List<Entry> = local.rosterAfter(after)

    override suspend fun appendRoster(
        head: String,
        entry: Entry,
        wraps: Map<String, String>,
        chain: String?,
    ): Appended {
        val server = server ?: return local.appendRoster(head, entry, wraps, chain)
        val appended = server.appendRoster(head, entry, wraps, chain)
        if (appended == Appended.Done) {
            val epoch = entry.epoch?.n ?: replayRoster(local.rosterAfter(-1)).epoch
            local.hold(entry, epoch, wraps.mapValues { (_, wrap) -> SyncBytes.b64urlDecode(wrap) }, chain?.let(SyncBytes::b64urlDecode))
        }
        return appended
    }

    override suspend fun key(
        epoch: Int,
        name: String,
    ): ByteArray? = local.key(epoch, name)

    override suspend fun putObject(
        path: String,
        bytes: ByteArray,
    ) = local.putObject(path, bytes)

    override suspend fun getObject(path: String): ByteArray? =
        local.getObject(path) ?: (server as? FileLayout)?.blob(path)?.also { local.putObject(path, it) }

    // The server first: a delete it refuses leaves the object held, so a later collection tries again.
    override suspend fun deleteObject(path: String) {
        server?.deleteObject(path)
        local.deleteObject(path)
    }

    override suspend fun changes(cursor: Long): Pair<List<ChangedObject>, Long> = local.changes(cursor)

    /**
     * Copies what the server holds that this device doesn't: roster entries, this device's wraps and
     * the chain boxes, and, with [objects], the objects after [cursor]. Returns the next cursor.
     */
    suspend fun download(
        me: String,
        cursor: Long,
        objects: Boolean,
    ): Long {
        val server = server ?: return cursor
        if (server is FileLayout) {
            server.pullRoster(local)
        } else {
            local.addEntries(
                server.rosterAfter(local.rosterAfter(-1).lastOrNull()?.seq ?: -1),
            )
        }
        val epochs = local.rosterAfter(-1).mapNotNull { it.epoch?.n }
        for (epoch in epochs) {
            for (name in listOf(me, "chain")) {
                if (local.key(epoch, name) == null) server.key(epoch, name)?.let { local.putKey(epoch, name, it) }
            }
        }
        if (!objects) return cursor
        if (server is FileLayout) {
            server.pullLogs(local)
            return cursor
        }
        if (server !is ChangeList) return cursor
        var next = cursor
        while (true) {
            val (changed, after) = server.changes(next)
            for (change in changed.filter { StorePaths.isObject(it.path) }) {
                if (local.getObject(change.path) == null) server.getObject(change.path)?.let { local.putObject(change.path, it) }
            }
            // A cursor that doesn't move would hand out the same page forever.
            if (changed.isEmpty() || after <= next) return next
            next = after
        }
    }

    /** Sends the server this device's own objects held after [heldCursor]; returns how far it got. */
    suspend fun upload(
        me: String,
        heldCursor: Long,
    ): Long {
        val server = server ?: return heldCursor
        var next = heldCursor
        while (true) {
            val (changed, after) = local.changes(next)
            if (changed.isEmpty()) return next
            for (change in changed.filter { it.path.startsWith("dev/$me/") }) {
                local.getObject(change.path)?.let { server.putObject(change.path, it) }
            }
            next = after
        }
    }
}
