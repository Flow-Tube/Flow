package io.github.aedev.flow.data.video.downloader.work

import java.io.File

/**
 * The working files of one download in app-private staging. Every name is keyed by video id, so two
 * downloads can never write to each other's files, whatever their titles.
 */
internal class DownloadStaging(
    directory: File,
    videoId: String,
) {
    private val stem = File(directory, videoId.replace(UNSAFE, "_")).path

    val videoPart = File("$stem.video.part")
    val audioPart = File("$stem.audio.part")
    val state = File("$stem.state.json")

    /** SABR writes beside the direct parts, so a failed fallback never costs the blocks already fetched. */
    val sabrVideoPart = File("$stem.sabr.video.part")
    val sabrAudioPart = File("$stem.sabr.audio.part")

    /** Marks the output as joined and checked, see [ReadyOutput]. */
    val ready = File("$stem.ready.json")

    fun output(extension: String) = File("$stem.out.$extension")

    /** Everything this download wrote to staging, including a half-made output. */
    fun clear() {
        listOf(videoPart, audioPart, state, File("${state.path}.tmp"), output("mp4"), output("m4a"), output("webm"))
            .forEach { it.delete() }
        ready.delete()
        clearSabrParts()
    }

    /** What the output was made from, once the output itself is checked and on disk. */
    fun clearInputs() {
        listOf(videoPart, audioPart, state, File("${state.path}.tmp")).forEach { it.delete() }
        clearSabrParts()
    }

    /** A half-made output, keeping the fetched parts for a retry. */
    fun clearOutputs() {
        listOf(output("mp4"), output("m4a"), output("webm")).forEach { it.delete() }
    }

    /** SABR cannot resume, so each fallback starts from empty files. */
    fun clearSabrParts() {
        listOf(sabrVideoPart, sabrAudioPart).forEach { it.delete() }
    }

    private companion object {
        val UNSAFE = Regex("[^A-Za-z0-9_-]")
    }
}
