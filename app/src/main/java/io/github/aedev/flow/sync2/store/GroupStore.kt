package io.github.aedev.flow.sync2.store

import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync2.roster.Entry
import io.github.aedev.flow.sync2.roster.entryHash

/**
 * The store a session works on: what this device holds, with the group's shared store, when it has
 * one: a Flow server, or files through [FileLayout]. Objects reach it through [upload].
 */
class GroupStore(
    val local: LocalStore,
    val server: Remote?,
    private val newBudget: () -> ReadBudget = ::ReadBudget,
) : ChangeList {
    /** A read of objects stopped on its [ReadBudget], short of the end. The next one goes on from where it got to. */
    var cutShort = false
        private set

    override suspend fun rosterAfter(after: Long): List<Entry> = local.rosterAfter(after)

    /** What the shared store answers, which decides, or what this device holds when it has none. */
    override suspend fun rosterView(): List<Entry> {
        val server = server ?: return local.entries()
        if (server !is FileLayout) return server.rosterView().also { local.addEntries(it) }
        server.pullRoster(local)
        val shown = server.rosterPaths().toSet()
        return local.entries().filter { StorePaths.roster(it.seq, entryHash(it)) in shown }
    }

    override val linear: Boolean get() = server?.linear ?: false

    override val heldHere: Boolean get() = server == null

    override fun usePins(pins: Set<String>) {
        server?.usePins(pins)
    }

    override suspend fun appendRoster(
        head: String,
        entry: Entry,
        wraps: Map<String, String>,
        chain: String?,
    ): Appended {
        val server = server ?: return local.appendRoster(head, entry, wraps, chain)
        val appended = server.appendRoster(head, entry, wraps, chain)
        if (appended == Appended.Done) {
            local.hold(
                entry,
                local.rosterAfter(-1),
                wraps.mapValues { (_, wrap) ->
                    SyncBytes.b64urlDecode(wrap)
                },
                chain?.let(SyncBytes::b64urlDecode),
            )
        }
        return appended
    }

    override suspend fun key(
        epoch: Int,
        kc: String,
        name: String,
    ): ByteArray? = local.key(epoch, kc, name)

    override suspend fun putObject(
        path: String,
        bytes: ByteArray,
    ) = local.putObject(path, bytes)

    override suspend fun getObject(path: String): ByteArray? = local.getObject(path) ?: blobFromFiles(path)

    /** A snapshot blob a file store holds and this device doesn't yet. */
    private suspend fun blobFromFiles(path: String): ByteArray? {
        val files = server as? FileLayout ?: return null
        return oneItem { files.blob(path) }?.takeIf { namesBlob(path, it) }?.also { local.putObject(path, it) }
    }

    // The server first: a delete it refuses leaves the object held, so a later collection tries again.
    override suspend fun deleteObject(path: String) {
        server?.deleteObject(path)
        local.deleteObject(path)
    }

    override suspend fun changes(cursor: Long): Pair<List<ChangedObject>, Long> = local.changes(cursor)

    override suspend fun forgetKey(
        epoch: Int,
        kc: String,
        name: String,
    ) = local.forgetKey(epoch, kc, name)

    /**
     * Copies what the server holds that this device doesn't: roster entries, this device's wraps and
     * the chain boxes, and, with [objects], the objects after [cursor], up to its [ReadBudget]. Returns the next
     * cursor, which stays before the first page holding an object this device couldn't keep, so that page is read again.
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
        val resolved = local.resolved()
        for ((epoch, kc, name) in keyNames(resolved.chain, resolved.standing, me)) {
            if (local.key(epoch, kc, name) == null) oneItem { server.key(epoch, kc, name) }?.let { local.putKey(epoch, kc, name, it) }
        }
        if (!objects) return cursor
        if (server is FileLayout) {
            cutShort = !server.pullLogs(local, newBudget())
            return cursor
        }
        if (server !is ChangeList) return cursor
        val check = ObjectCheck(me, resolved.chain)
        val budget = newBudget()
        var next = cursor
        var unkept: Long? = null
        while (true) {
            val (changed, after) = server.changes(next)
            var kept = true
            for (change in changed.filter { StorePaths.isObject(it.path) && !check.isOwn(it.path) }) {
                if (!keep(change.path, server, check)) kept = false
            }
            if (!kept) unkept = unkept ?: next
            // A cursor that doesn't move would hand out the same page forever.
            if (changed.isEmpty() || after <= next) return unkept ?: next
            next = after
            if (!budget.take(changed.size)) {
                cutShort = true
                return unkept ?: next
            }
        }
    }

    private suspend fun keep(
        path: String,
        server: ChangeList,
        check: ObjectCheck,
    ): Boolean {
        if (local.getObject(path) != null) return true
        val bytes =
            try {
                server.getObject(path) ?: return true
            } catch (e: StoreException) {
                if (e.stopsOnlyItsItem) return false else throw e
            }
        if (!check.admits(path, bytes)) return false
        local.putObject(path, bytes)
        return true
    }

    /**
     * Sends the server this device's own objects held after [heldCursor], in order. The first one it refuses
     * ends the upload: skipping it would leave a gap other devices wait at.
     */
    suspend fun upload(
        me: String,
        heldCursor: Long,
    ): Uploaded {
        val server = server ?: return Uploaded(heldCursor, null)
        var next = heldCursor
        while (true) {
            val (changed, after) = local.changes(next)
            if (changed.isEmpty()) return Uploaded(next, null)
            for (change in changed.filter { it.path.startsWith("dev/$me/") }) {
                try {
                    local.getObject(change.path)?.let { server.putObject(change.path, it) }
                } catch (e: StoreException.Refused) {
                    return Uploaded(next, e)
                }
            }
            next = after
        }
    }
}

/** How far an upload got, and the refusal that ended it early. */
class Uploaded(
    val cursor: Long,
    val refused: StoreException.Refused?,
)
