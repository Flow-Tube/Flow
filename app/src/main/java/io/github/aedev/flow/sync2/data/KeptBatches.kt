package io.github.aedev.flow.sync2.data

import io.github.aedev.flow.sync2.changes.IncomingBatch
import io.github.aedev.flow.sync2.changes.batchContent
import io.github.aedev.flow.sync2.changes.parseBatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A batch kept for a linked profile that isn't active, and who sent it. */
class Kept(
    val sender: String,
    val batch: IncomingBatch,
    internal val id: Long,
)

/** Batches for linked profiles that aren't active (spec/protocol.md, Profiles), one file each under [root]. */
class KeptBatches(
    private val root: File,
    private val dao: LinkedProfileDao,
) {
    private fun file(id: Long) = File(root, id.toString())

    suspend fun keep(
        link: String,
        sender: String,
        batch: IncomingBatch,
    ) {
        val id = dao.keep(KeptBatchRow(link = link, sender = sender, collection = batch.collection))
        withContext(Dispatchers.IO) {
            root.mkdirs()
            file(id).writeBytes(batchContent(batch.collection, batch.head, batch.link, batch.lines))
        }
    }

    /** The oldest batch kept for [link]; a row whose file is gone is dropped. */
    suspend fun first(link: String): Kept? {
        while (true) {
            val row = dao.firstKept(link) ?: return null
            val content = withContext(Dispatchers.IO) { file(row.id).takeIf { it.isFile }?.readBytes() }
            if (content != null) return Kept(row.sender, parseBatch(content), row.id)
            dao.applied(row.id)
        }
    }

    suspend fun applied(kept: Kept) {
        dao.applied(kept.id)
        withContext(Dispatchers.IO) { file(kept.id).delete() }
    }

    suspend fun count(link: String): Int = dao.keptCount(link)

    /** Drops what was kept for a link this device stopped syncing. */
    suspend fun drop(link: String) {
        for (id in dao.keptIds(link)) {
            dao.applied(id)
            withContext(Dispatchers.IO) { file(id).delete() }
        }
    }
}
