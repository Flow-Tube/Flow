package io.github.aedev.flow.data.contentfilter.aislist

import androidx.core.util.AtomicFile
import java.io.File

/**
 * The device's one copy of the list, in `noBackupFilesDir`: no backup, sync or profile folder
 * reads it, and it is the same for every profile. A write goes to a side file and replaces the
 * stored one only when complete, so a crash mid-write keeps the previous list.
 */
internal class AiListStore(
    directory: File,
) : AiListStorage {
    private val file = AtomicFile(File(directory, FILE_NAME))

    fun exists(): Boolean = file.baseFile.exists()

    override fun load(): StoredAiList? =
        runCatching { aiListJson.decodeFromString(StoredAiList.serializer(), file.readFully().decodeToString()) }.getOrNull()

    override fun commit(list: StoredAiList) {
        file.baseFile.parentFile?.mkdirs()
        val bytes = aiListJson.encodeToString(StoredAiList.serializer(), list).encodeToByteArray()
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
    }

    private companion object {
        const val FILE_NAME = "list.json"
    }
}
