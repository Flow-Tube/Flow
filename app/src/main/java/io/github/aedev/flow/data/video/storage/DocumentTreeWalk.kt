package io.github.aedev.flow.data.video.storage

/**
 * The files in a picked folder and in the folders below it down to [maxDepth] levels, as
 * [children] lists them. Hidden entries are skipped, which leaves out the system's `.trashed-` and
 * `.pending-` files. A provider can be slow and a folder huge, so the walk stops after [limit]
 * listed documents.
 */
internal fun filesInTree(
    rootId: String,
    maxDepth: Int,
    limit: Int,
    children: (String) -> List<TreeDocument>,
): List<TreeDocument> {
    val files = mutableListOf<TreeDocument>()
    var listed = 0
    val pending = ArrayDeque(listOf(rootId to 1))
    while (pending.isNotEmpty() && listed < limit) {
        val (folderId, depth) = pending.removeFirst()
        for (document in children(folderId)) {
            if (++listed > limit) break
            if (document.name.startsWith('.')) continue
            when {
                !document.isFolder -> files += document
                depth < maxDepth -> pending += document.id to depth + 1
            }
        }
    }
    return files
}
