package io.github.aedev.flow.data.video.storage

import java.io.File

/** A download folder the user chose: a file-system path, the system picker's tree, or both. */
data class DownloadLocation(
    val path: String? = null,
    val treeUri: String? = null,
) {
    val isSet: Boolean get() = !path.isNullOrBlank() || !treeUri.isNullOrBlank()

    companion object {
        val DEFAULT = DownloadLocation()

        /** Music follows the video location until it has one of its own. */
        fun forDownload(
            isMusic: Boolean,
            video: DownloadLocation,
            music: DownloadLocation,
        ): DownloadLocation = if (isMusic && music.isSet) music else video
    }
}

/**
 * Where a download is written. With [exportTreeUri] the file is written to [directory] first and
 * copied into that picked folder once finished. [fellBack] means the chosen location could not be
 * used and a default folder took its place.
 */
data class DownloadDestination(
    val directory: File,
    val exportTreeUri: String? = null,
    val fellBack: Boolean = false,
)

/**
 * Picks the folder for a download. A chosen path is written directly when it can be; otherwise a
 * picked tree the app still has access to is exported to; otherwise the first usable default wins.
 * The last default is taken even when unusable, so there is always an answer.
 */
fun resolveDownloadDestination(
    chosen: DownloadLocation,
    defaults: List<File>,
    staging: File,
    isUsableDirectory: (File) -> Boolean,
    hasTreeAccess: (String) -> Boolean,
): DownloadDestination {
    chosen.path
        ?.takeIf { it.isNotBlank() }
        ?.let(::File)
        ?.takeIf(isUsableDirectory)
        ?.let { return DownloadDestination(it) }
    chosen.treeUri
        ?.takeIf { it.isNotBlank() && hasTreeAccess(it) }
        ?.let { return DownloadDestination(staging, exportTreeUri = it) }
    val fallback = defaults.firstOrNull(isUsableDirectory) ?: defaults.last()
    return DownloadDestination(fallback, fellBack = chosen.isSet)
}

/**
 * The picked folder Flow can no longer open, as after a reinstall: the saved location comes back
 * with the settings backup, but Android drops the folder's grant. Null when every picked folder can
 * still be used, or when All files access reaches the folder through its path anyway.
 */
fun lostDownloadTree(
    locations: List<DownloadLocation>,
    grantedTrees: Set<String>,
    hasAllFilesAccess: Boolean,
): String? =
    locations
        .firstOrNull { location ->
            val tree = location.treeUri
            !tree.isNullOrBlank() && tree !in grantedTrees && !(hasAllFilesAccess && !location.path.isNullOrBlank())
        }?.treeUri

/**
 * The file-system path behind a storage provider document id such as `primary:Movies/Flow`, or
 * null when the id does not name a place on a storage volume.
 */
fun documentIdToPath(
    documentId: String,
    primaryRoot: String,
): String? {
    if (documentId.startsWith(RAW_PREFIX)) return documentId.removePrefix(RAW_PREFIX).takeIf { it.startsWith("/") }
    val separator = documentId.indexOf(':')
    if (separator <= 0) return null
    val volume = documentId.substring(0, separator)
    val relative = documentId.substring(separator + 1).trim('/')
    val root =
        when {
            volume.equals(PRIMARY_VOLUME, ignoreCase = true) -> primaryRoot.trimEnd('/')
            VolumeUuid.matches(volume) -> "/storage/$volume"
            else -> return null
        }
    return if (relative.isEmpty()) root else "$root/$relative"
}

private const val RAW_PREFIX = "raw:"
private const val PRIMARY_VOLUME = "primary"
private val VolumeUuid = Regex("[0-9A-Fa-f]+-[0-9A-Fa-f]+")
