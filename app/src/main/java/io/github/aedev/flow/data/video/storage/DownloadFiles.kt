package io.github.aedev.flow.data.video.storage

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.File

/**
 * File operations on a stored download path, which is either an absolute file path or the
 * `content://` document of a file exported into a folder picked with the system picker.
 */
object DownloadFiles {
    private const val TAG = "DownloadFiles"
    private const val COPY_BUFFER_BYTES = 1 shl 20
    private const val TREE_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    fun isDocument(path: String): Boolean = path.startsWith(ContentResolver.SCHEME_CONTENT + "://")

    fun exists(
        context: Context,
        path: String,
    ): Boolean {
        if (!isDocument(path)) return File(path).exists()
        return runCatching {
            context.contentResolver
                .query(Uri.parse(path), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { it.moveToFirst() } == true
        }.getOrDefault(false)
    }

    fun displayName(
        context: Context,
        path: String,
    ): String? {
        if (!isDocument(path)) return File(path).name
        return runCatching {
            context.contentResolver
                .query(Uri.parse(path), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
    }

    /** Deletes an exported document; true when it is gone afterwards. */
    fun deleteDocument(
        context: Context,
        path: String,
    ): Boolean {
        if (!exists(context, path)) return true
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(path)) }
            .onFailure { Log.w(TAG, "Could not delete $path", it) }
        return !exists(context, path)
    }

    fun hasTreeAccess(
        context: Context,
        treeUri: String,
    ): Boolean {
        val uri = Uri.parse(treeUri)
        return context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
    }

    /** The picked folders Flow may still write to. */
    fun grantedTrees(context: Context): Set<String> =
        context.contentResolver.persistedUriPermissions
            .filter { it.isWritePermission }
            .mapTo(mutableSetOf()) { it.uri.toString() }

    fun releaseTree(
        context: Context,
        treeUri: String,
    ) {
        runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(treeUri), TREE_FLAGS) }
    }

    /** The file-system path of a picked folder, when the provider exposes one. */
    fun treePath(treeUri: String): String? =
        runCatching {
            documentIdToPath(DocumentsContract.getTreeDocumentId(Uri.parse(treeUri)), primaryRoot())
        }.getOrNull()

    /** The file-system path behind an exported document, so a folder scan can recognise it. */
    fun documentPath(path: String): String? =
        runCatching {
            documentIdToPath(DocumentsContract.getDocumentId(Uri.parse(path)), primaryRoot())
        }.getOrNull()

    /**
     * Copies [source] into the picked folder [treeUri], or into [parentDocument] inside it, as
     * [displayName], and returns the new document or null. Providers rename on a clash, so the name
     * that was actually used is read back from the document.
     */
    fun exportToTree(
        context: Context,
        source: File,
        treeUri: String,
        displayName: String = source.name,
        parentDocument: String? = null,
    ): String? {
        val resolver = context.contentResolver
        val target =
            runCatching {
                val folder = parentDocument?.let(Uri::parse) ?: treeRoot(treeUri)
                DocumentsContract.createDocument(resolver, folder, mimeTypeOf(File(displayName)), displayName)
            }.onFailure { Log.w(TAG, "Could not create $displayName in $treeUri", it) }
                .getOrNull() ?: return null
        val copied =
            runCatching {
                resolver.openOutputStream(target, "w")?.use { output ->
                    source.inputStream().use { it.copyTo(output, COPY_BUFFER_BYTES) }
                } != null
            }.onFailure { Log.w(TAG, "Could not copy ${source.name} to $target", it) }
                .getOrDefault(false)
        if (!copied) {
            runCatching { DocumentsContract.deleteDocument(resolver, target) }
            return null
        }
        return target.toString()
    }

    /** The folder [name] directly inside the picked folder [treeUri], created when missing, or null. */
    fun ensureTreeDirectory(
        context: Context,
        treeUri: String,
        name: String,
    ): String? {
        val resolver = context.contentResolver
        val tree = Uri.parse(treeUri)
        return runCatching {
            val root = treeRoot(treeUri)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(root))
            val existing =
                resolver
                    .query(
                        children,
                        arrayOf(
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE,
                        ),
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        var found: String? = null
                        while (found == null && cursor.moveToNext()) {
                            if (cursor.getString(1) == name && cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                                found = cursor.getString(0)
                            }
                        }
                        found
                    }
            existing?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it).toString() }
                ?: DocumentsContract.createDocument(resolver, root, DocumentsContract.Document.MIME_TYPE_DIR, name)?.toString()
        }.onFailure { Log.w(TAG, "Could not open folder $name in $treeUri", it) }
            .getOrNull()
    }

    /** Whether a document named [name] already sits in [parentDocument], or at the root of [treeUri]. */
    fun treeHasChild(
        context: Context,
        treeUri: String,
        parentDocument: String?,
        name: String,
    ): Boolean {
        val tree = Uri.parse(treeUri)
        return runCatching {
            val parent = parentDocument?.let(Uri::parse) ?: treeRoot(treeUri)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
            context.contentResolver
                .query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    var hit = false
                    while (!hit && cursor.moveToNext()) hit = cursor.getString(0) == name
                    hit
                } == true
        }.getOrDefault(false)
    }

    /** Deletes a folder once nothing is left in it; true when it is gone afterwards. */
    fun deleteIfEmpty(
        context: Context,
        location: String,
    ): Boolean {
        if (!isDocument(location)) {
            val folder = File(location)
            return !folder.exists() || (folder.listFiles()?.isEmpty() == true && folder.delete())
        }
        val uri = Uri.parse(location)
        val empty =
            runCatching {
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getDocumentId(uri))
                context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use {
                    it.count == 0
                } == true
            }.getOrDefault(false)
        if (!empty) return false
        return runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
    }

    /** Moves [source] into [directory] as [name], copying when a rename cannot cross the two folders. */
    fun moveInto(
        source: File,
        directory: File,
        name: String = source.name,
    ): File? {
        directory.mkdirs()
        val target = File(directory, name)
        if (target.exists()) return null
        if (source.renameTo(target)) return target
        return runCatching {
            source.copyTo(target, overwrite = false)
            source.delete()
            target
        }.onFailure { Log.w(TAG, "Could not move ${source.name} to $directory", it) }
            .getOrNull()
    }

    private fun treeRoot(treeUri: String): Uri {
        val tree = Uri.parse(treeUri)
        return DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    }

    // The provider checks the display name's extension against this type, so it must come from the
    // same table or the file gets a second extension.
    private fun mimeTypeOf(file: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"

    @Suppress("DEPRECATION")
    private fun primaryRoot(): String = Environment.getExternalStorageDirectory().absolutePath
}
