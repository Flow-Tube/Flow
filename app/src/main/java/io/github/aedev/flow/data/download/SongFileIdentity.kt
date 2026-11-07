package io.github.aedev.flow.data.download

import io.github.aedev.flow.innertube.models.response.PlayerResponse.StreamingData.Format
import java.io.IOException

/** What makes two streams of a song the same file, whichever client or url they came through. */
internal fun Format.fileId(): String = "$itag.$lastModified.${xtags.orEmpty()}"

/**
 * The stream to read next when the song's cache already holds the file [cachedFileId]. A song is
 * read as one file at one byte offset, so the rest has to come from that file, even if [best] is
 * another format now: Auto quality picks Opus on Wi-Fi and AAC on mobile data. Null when that file
 * is no longer on offer.
 */
internal fun streamToContinue(
    cachedFileId: String?,
    best: Format,
    offered: List<Format>,
): Format? =
    if (cachedFileId == null || best.fileId() == cachedFileId) {
        best
    } else {
        offered.firstOrNull { it.fileId() == cachedFileId && !it.url.isNullOrEmpty() }
    }

/**
 * The song's cached part can't be continued. Not an [IOException]: the loader would retry the read
 * inside the extractor it already has, while the player's recovery clears the cache and starts over.
 */
internal class SongFileGoneException(
    videoId: String,
) : IllegalStateException("The cached file of $videoId is no longer on offer")
