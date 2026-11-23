package io.github.aedev.flow.data.video.downloader.work

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

/**
 * A joined, tagged and checked file waiting in staging to be copied into its folder. Its parts are
 * gone by then, so a copy that fails is retried from this file alone rather than downloaded again.
 */
@Serializable
internal data class ReadyOutput(
    val extension: String,
    val quality: String,
    val swappedTrack: String? = null,
    val savedQuality: String? = null,
    val wantedQuality: String? = null,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun read(file: File): ReadyOutput? = runCatching { json.decodeFromString(serializer(), file.readText()) }.getOrNull()

        fun write(
            file: File,
            ready: ReadyOutput,
        ) {
            FileOutputStream(file).use { output ->
                output.write(json.encodeToString(serializer(), ready).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
        }
    }
}
