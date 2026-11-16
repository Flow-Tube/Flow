package io.github.aedev.flow.data.video.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log

/** A file or folder inside a picked folder, as the storage provider lists it. */
internal data class TreeDocument(
    val id: String,
    val name: String,
    val isFolder: Boolean,
    val sizeBytes: Long = 0L,
    val modifiedAt: Long = 0L,
)

/** A file found in a picked folder: its `content://` document and what the provider says about it. */
internal data class TreeFile(
    val uri: String,
    val name: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
)

/** Lists picked download folders through their tree grant, which is how Flow reads them without media access. */
internal object DownloadTree {
    private const val TAG = "DownloadTree"
    private const val MAX_DOCUMENTS = 20_000

    private val Columns =
        arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )

    /** The files in [treeUri] and in the folders [maxDepth] levels below it; empty when it can't be read. */
    fun files(
        context: Context,
        treeUri: String,
        maxDepth: Int,
    ): List<TreeFile> {
        val tree = Uri.parse(treeUri)
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return emptyList()
        return filesInTree(rootId, maxDepth, MAX_DOCUMENTS) { children(context, tree, it) }
            .map { TreeFile(DocumentsContract.buildDocumentUriUsingTree(tree, it.id).toString(), it.name, it.sizeBytes, it.modifiedAt) }
    }

    private fun children(
        context: Context,
        tree: Uri,
        folderId: String,
    ): List<TreeDocument> =
        runCatching {
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId)
            context.contentResolver
                .query(uri, Columns, null, null, null)
                ?.use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            val id = cursor.getString(0) ?: continue
                            add(
                                TreeDocument(
                                    id = id,
                                    name = cursor.getString(1).orEmpty(),
                                    isFolder = cursor.getString(2) == Document.MIME_TYPE_DIR,
                                    sizeBytes = if (cursor.isNull(3)) 0L else cursor.getLong(3),
                                    modifiedAt = if (cursor.isNull(4)) 0L else cursor.getLong(4),
                                ),
                            )
                        }
                    }
                }.orEmpty()
        }.onFailure { Log.w(TAG, "Could not list $folderId in $tree", it) }
            .getOrDefault(emptyList())
}
