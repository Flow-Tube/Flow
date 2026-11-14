package io.github.aedev.flow.data.contentfilter.aislist

import androidx.core.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Channel ID to normalized handle, learned from every item that carries both. The list holds only
 * handles while most feed rows carry only a channel ID, so this is how a cached Home row or a
 * resolved Short still matches a listed handle. Least recently used entries go first past [capacity].
 */
internal class ChannelHandleIndex(
    directory: File?,
    private val scope: CoroutineScope?,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val file = directory?.let { AtomicFile(File(it, FILE_NAME)) }
    private val entries =
        object : LinkedHashMap<String, String>(INITIAL_SIZE, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > capacity
        }
    private val loaded = AtomicBoolean(false)
    private val saveScheduled = AtomicBoolean(false)

    fun handleFor(channelId: String): String? = synchronized(entries) { entries[channelId] }

    fun learn(
        channelId: String,
        normalizedHandle: String,
    ) {
        val changed = synchronized(entries) { entries.put(channelId, normalizedHandle) != normalizedHandle }
        if (changed) scheduleSave()
    }

    /** Reads the saved index once; pairs learned before it finishes win over the saved ones. */
    fun load() {
        if (!loaded.compareAndSet(false, true)) return
        val saved =
            runCatching { file?.readFully()?.decodeToString()?.let { aiListJson.decodeFromString(INDEX_SERIALIZER, it) } }
                .getOrNull() ?: return
        synchronized(entries) {
            val learned = LinkedHashMap(entries)
            entries.clear()
            saved.entries.forEach { (id, handle) -> entries[id] = handle }
            learned.forEach { (id, handle) -> entries[id] = handle }
        }
    }

    private fun scheduleSave() {
        val target = file ?: return
        val scope = scope ?: return
        if (!saveScheduled.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            delay(SAVE_DELAY_MS)
            saveScheduled.set(false)
            val copy = synchronized(entries) { LinkedHashMap(entries) }
            runCatching {
                target.baseFile.parentFile?.mkdirs()
                val stream = target.startWrite()
                try {
                    stream.write(aiListJson.encodeToString(INDEX_SERIALIZER, copy).encodeToByteArray())
                    target.finishWrite(stream)
                } catch (error: Throwable) {
                    target.failWrite(stream)
                    throw error
                }
            }
        }
    }

    private companion object {
        const val FILE_NAME = "handle-index.json"
        const val DEFAULT_CAPACITY = 50_000
        const val INITIAL_SIZE = 1024
        const val LOAD_FACTOR = 0.75f
        const val SAVE_DELAY_MS = 3 * 60 * 1000L
        val INDEX_SERIALIZER = MapSerializer(String.serializer(), String.serializer())
    }
}
