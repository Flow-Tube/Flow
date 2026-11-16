package io.github.aedev.flow.sync2.store

/** Plain storage that holds the store layout as files: WebDAV now, S3 later (spec/store.md, Backends). */
interface FileStore {
    /** Where the files live, without credentials. */
    val location: String

    /** The file's bytes, or null when there is none. */
    suspend fun get(path: String): ByteArray?

    /** Writes a file, making the folders it needs. No path is written twice (spec/store.md, Writing rules). */
    suspend fun put(
        path: String,
        bytes: ByteArray,
    )

    /** Deletes a file, or a folder with everything in it; one already gone counts as deleted. */
    suspend fun delete(path: String)

    /** The names directly inside [folder], empty when it doesn't exist. */
    suspend fun list(folder: String): List<String>

    /** A tag that changes whenever anything inside [folder] does, or null when the store offers none. */
    suspend fun tag(folder: String): String?
}
