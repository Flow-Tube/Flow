package io.github.aedev.flow.data.local

import java.io.BufferedReader
import java.net.URI
import java.text.Normalizer
import java.time.OffsetDateTime
import java.util.Locale

internal data class YouTubeTakeoutSubscription(
    val channelId: String,
    val channelName: String,
)

/** A playlist as `playlists.csv` lists it; [createdAt] is null when the row carries no timestamp. */
internal data class TakeoutPlaylistInfo(
    val id: String,
    val title: String,
    val createdAt: Long?,
)

/** A song saved to the YouTube Music library; [artists] are joined for display. */
internal data class TakeoutLibrarySong(
    val videoId: String,
    val title: String,
    val album: String,
    val artists: String,
)

internal sealed interface YouTubeTakeoutCsvContent {
    data class Subscriptions(
        val rows: List<YouTubeTakeoutSubscription>,
    ) : YouTubeTakeoutCsvContent

    data class PlaylistVideos(
        val videoIds: List<String>,
    ) : YouTubeTakeoutCsvContent

    data class PlaylistMetadata(
        val playlists: List<TakeoutPlaylistInfo>,
    ) : YouTubeTakeoutCsvContent

    data class MusicLibrarySongs(
        val songs: List<TakeoutLibrarySong>,
    ) : YouTubeTakeoutCsvContent

    data object Unsupported : YouTubeTakeoutCsvContent
}

private val youtubeChannelIdPattern = Regex("UC[A-Za-z0-9_-]{22}")
private val youtubePlaylistIdPattern = Regex("PL[A-Za-z0-9_-]{16,}")
private val youtubeVideoIdPattern = Regex("[A-Za-z0-9_-]{11}")
private const val MAX_IMPORTED_NAME_CHARACTERS = 1_000
private const val MAX_TAKEOUT_ENTRY_NAME_CHARACTERS = 4_096
private const val MAX_TAKEOUT_LEAF_NAME_CHARACTERS = 512
private const val MAX_TAKEOUT_PLAYLISTS = 2_000
private val LIBRARY_COLUMNS = 3..8

internal fun isYouTubeTakeoutCsvEntry(entryName: String): Boolean =
    youTubeTakeoutSegments(entryName)?.last()?.endsWith(".csv", ignoreCase = true) == true

/** An HTML file one folder inside the YouTube product, where Takeout puts the history files in every language. */
internal fun isYouTubeTakeoutHtmlEntry(entryName: String): Boolean =
    youTubeTakeoutSegments(entryName)?.let { segments ->
        segments.size == 4 && segments.last().endsWith(".html", ignoreCase = true)
    } == true

/** A JSON file one folder inside the YouTube product: the watch or search history, when the export chose JSON. */
internal fun isYouTubeTakeoutJsonEntry(entryName: String): Boolean =
    youTubeTakeoutSegments(entryName)?.let { segments ->
        segments.size == 4 && segments.last().endsWith(".json", ignoreCase = true)
    } == true

private fun youTubeTakeoutSegments(entryName: String): List<String>? {
    if (entryName.length > MAX_TAKEOUT_ENTRY_NAME_CHARACTERS) return null
    val segments = entryName.replace('\\', '/').split('/')
    val product = segments.getOrNull(1).orEmpty()
    val isYouTubeProduct =
        product.equals("YouTube", ignoreCase = true) ||
            (
                product.startsWith("YouTube ", ignoreCase = true) &&
                    product.endsWith("YouTube Music", ignoreCase = true)
            )
    val isValid =
        segments.size >= 3 &&
            segments.first().equals("Takeout", ignoreCase = true) &&
            isYouTubeProduct &&
            segments.none { it.isEmpty() || it == "." || it == ".." } &&
            segments.last().length <= MAX_TAKEOUT_LEAF_NAME_CHARACTERS
    return segments.takeIf { isValid }
}

internal fun String.takeoutParentPath(): String = replace('\\', '/').substringBeforeLast('/', "")

internal fun validateYouTubeTakeoutPlaylistCount(
    videoFileCount: Int,
    metadataTitleCount: Int,
) {
    if (videoFileCount > MAX_TAKEOUT_PLAYLISTS || metadataTitleCount > MAX_TAKEOUT_PLAYLISTS) {
        throw IllegalArgumentException("invalid_format")
    }
}

internal fun readYouTubeTakeoutCsv(
    reader: BufferedReader,
    budget: YouTubeTakeoutCsvBudget = YouTubeTakeoutCsvBudget(),
): YouTubeTakeoutCsvContent {
    val csvReader = TakeoutCsvReader(reader, budget)
    val header =
        csvReader.nextNonBlankRecord() as? CsvRecordResult.Record
            ?: return csvReader.rejectIgnoredAsUnsupported()
    val firstData =
        csvReader.nextNonBlankRecord() as? CsvRecordResult.Record
            ?: return csvReader.rejectIgnoredAsUnsupported()

    val firstSubscription = firstData.fields.toSubscription()
    val firstPlaylistVideo = firstData.fields.toPlaylistVideoId()
    val firstPlaylist = firstData.fields.toPlaylistInfo()
    val firstSong = firstData.fields.toLibrarySong()

    return when {
        header.fields.size >= 3 && header.fields.toSubscription() == null && firstSubscription != null -> {
            csvReader.readRows(
                first = firstSubscription,
                weight = { row -> row.channelId.length + row.channelName.length },
                parse = { fields -> fields.toSubscription() },
                build = { rows -> YouTubeTakeoutCsvContent.Subscriptions(rows) },
            )
        }

        header.fields.size == 2 && header.fields.toPlaylistVideoId() == null && firstPlaylistVideo != null -> {
            csvReader.readRows(
                first = firstPlaylistVideo,
                weight = String::length,
                parse = { fields -> fields.toPlaylistVideoId() },
                build = { videoIds -> YouTubeTakeoutCsvContent.PlaylistVideos(videoIds) },
            )
        }

        header.fields.size >= 11 && header.fields.toPlaylistInfo() == null && firstPlaylist != null -> {
            csvReader.readRows(
                first = firstPlaylist,
                weight = { it.id.length + it.title.length },
                parse = { fields -> fields.toPlaylistInfo() },
                build = { playlists -> YouTubeTakeoutCsvContent.PlaylistMetadata(playlists) },
            )
        }

        header.fields.size in LIBRARY_COLUMNS && header.fields.toLibrarySong() == null && firstSong != null -> {
            csvReader.readRows(
                first = firstSong,
                weight = { it.videoId.length + it.title.length + it.album.length + it.artists.length },
                parse = { fields -> fields.toLibrarySong() },
                build = { songs -> YouTubeTakeoutCsvContent.MusicLibrarySongs(songs) },
            )
        }

        else -> {
            csvReader.rejectIgnoredAsUnsupported()
        }
    }
}

internal fun resolveYouTubeTakeoutPlaylistNames(
    filenames: Collection<String>,
    metadataTitles: Collection<String>,
    fallbackPlaylistName: String,
): Map<String, String> {
    validateYouTubeTakeoutPlaylistCount(filenames.size, metadataTitles.size)
    val remainingFilenames = filenames.toMutableList()
    val remainingTitles = metadataTitles.filter { it.isNotBlank() }.toMutableList()
    val resolved = linkedMapOf<String, String>()
    val normalizedFilenames =
        filenames.associateWith { filename ->
            filename.takeoutLeafStem().withoutEnglishVideosSuffix().normalizedTakeoutName()
        }

    val titleCandidates =
        remainingTitles
            .groupBy(String::normalizedTakeoutName)
            .mapNotNull { (normalizedTitle, titles) ->
                if (normalizedTitle.isEmpty()) return@mapNotNull null
                val matchCount = normalizedFilenames.values.count { filename -> filename.contains(normalizedTitle) }
                Triple(titles, normalizedTitle, matchCount).takeIf { matchCount > 0 }
            }.sortedWith(
                compareBy<Triple<List<String>, String, Int>> { (_, _, matchCount) -> matchCount }
                    .thenByDescending { (_, normalizedTitle) -> normalizedTitle.length },
            )

    titleCandidates.forEach { (titles, normalizedTitle) ->
        val matchingFilenames =
            remainingFilenames.filter { filename ->
                normalizedFilenames.getValue(filename).contains(normalizedTitle)
            }
        val hasOneExactTitle = titles.all { title -> title == titles.first() }
        if (hasOneExactTitle && matchingFilenames.size == titles.size) {
            matchingFilenames.zip(titles).forEach { (filename, title) ->
                resolved[filename] = title
            }
            remainingFilenames.removeAll(matchingFilenames.toSet())
            titles.forEach { title -> remainingTitles.remove(title) }
        }
    }

    if (remainingFilenames.size == 1 && remainingTitles.size == 1) {
        resolved[remainingFilenames.removeAt(0)] = remainingTitles.removeAt(0)
    }

    remainingFilenames.forEach { filename ->
        resolved[filename] = filename.fallbackTakeoutPlaylistName(fallbackPlaylistName)
    }
    return resolved
}

private fun <T> TakeoutCsvReader.readRows(
    first: T,
    weight: (T) -> Int,
    parse: (List<String>) -> T?,
    build: (List<T>) -> YouTubeTakeoutCsvContent,
): YouTubeTakeoutCsvContent {
    stageContent(weight(first))
    val rows = mutableListOf(first)
    while (true) {
        when (val result = nextNonBlankRecord()) {
            CsvRecordResult.End -> {
                commitContent()
                return build(rows)
            }

            CsvRecordResult.Malformed -> {
                return rejectTargetAsUnsupported()
            }

            is CsvRecordResult.Record -> {
                parse(result.fields)?.let { row ->
                    stageContent(weight(row))
                    rows += row
                }
            }
        }
    }
}

private fun List<String>.toSubscription(): YouTubeTakeoutSubscription? {
    if (size < 3) return null
    val channelId = this[0].trim().trimStart('\uFEFF')
    if (!youtubeChannelIdPattern.matches(channelId)) return null

    val channelUri = runCatching { URI(this[1].trim()) }.getOrNull() ?: return null
    val scheme = channelUri.scheme?.lowercase(Locale.ROOT)
    val host = channelUri.host?.lowercase(Locale.ROOT)
    if (scheme != "http" && scheme != "https") return null
    if (host != "youtube.com" && host != "www.youtube.com") return null
    if (channelUri.userInfo != null || channelUri.port != -1 || channelUri.query != null || channelUri.fragment != null) return null
    val expectedPath = "/channel/$channelId"
    if (channelUri.path != expectedPath && channelUri.path != "$expectedPath/") return null

    val channelName = this[2].validatedTakeoutName() ?: return null
    return YouTubeTakeoutSubscription(channelId, channelName)
}

private fun List<String>.toPlaylistVideoId(): String? {
    if (size != 2) return null
    val videoId = this[0].trim().trimStart('\uFEFF')
    if (!youtubeVideoIdPattern.matches(videoId)) return null
    val timestamp = this[1].trim()
    if (timestamp.isNotEmpty() && runCatching { OffsetDateTime.parse(timestamp) }.isFailure) return null
    return videoId
}

/**
 * The create timestamp is found by its shape, not its column: the first field after the id that
 * reads as a date, which is the create timestamp and is followed by the update one.
 */
private fun List<String>.toPlaylistInfo(): TakeoutPlaylistInfo? {
    if (size < 11) return null
    val playlistId = this[0].trim().trimStart('\uFEFF')
    if (!youtubePlaylistIdPattern.matches(playlistId)) return null
    val title = this[10].validatedTakeoutName() ?: return null
    val createdAt =
        drop(1).firstNotNullOfOrNull { field ->
            runCatching { OffsetDateTime.parse(field.trim()).toInstant().toEpochMilli() }.getOrNull()
        }
    return TakeoutPlaylistInfo(playlistId, title, createdAt)
}

/**
 * A row of the YouTube Music library's songs file: the video id, then the title, album and artists.
 * The account's own uploads (`video metadata`) also start with a video id, but run longer and carry
 * timestamps and channel ids, which no library row does.
 */
private fun List<String>.toLibrarySong(): TakeoutLibrarySong? {
    if (size !in LIBRARY_COLUMNS) return null
    val videoId = this[0].trim().trimStart('\uFEFF')
    if (!youtubeVideoIdPattern.matches(videoId)) return null
    val rest = drop(1).map(String::trim)
    if (rest.any { youtubeChannelIdPattern.matches(it) || runCatching { OffsetDateTime.parse(it) }.isSuccess }) return null
    val title = rest[0].validatedTakeoutName() ?: return null
    return TakeoutLibrarySong(
        videoId = videoId,
        title = title,
        album = rest.getOrNull(1)?.validatedTakeoutName().orEmpty(),
        artists = rest.drop(2).mapNotNull { it.validatedTakeoutName() }.joinToString(", "),
    )
}

private fun String.validatedTakeoutName(): String? =
    trim().takeIf { value ->
        value.isNotEmpty() &&
            value.length <= MAX_IMPORTED_NAME_CHARACTERS &&
            value.none { character ->
                character.isISOControl() || character == '\u2028' || character == '\u2029'
            }
    }

private fun String.normalizedTakeoutName(): String =
    Normalizer
        .normalize(this, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .filter(Char::isTakeoutNameCharacter)

private fun Char.isTakeoutNameCharacter(): Boolean =
    isLetterOrDigit() ||
        Character.isSurrogate(this) ||
        when (Character.getType(this)) {
            Character.MATH_SYMBOL.toInt(),
            Character.CURRENCY_SYMBOL.toInt(),
            Character.MODIFIER_SYMBOL.toInt(),
            Character.OTHER_SYMBOL.toInt(),
            -> true

            else -> false
        }

private fun String.takeoutLeafStem(): String {
    val leaf = replace('\\', '/').substringAfterLast('/')
    return leaf.substringBeforeLast('.', leaf)
}

// Takeout names playlist files "<title>-videos.csv" or "<title> videos.csv", and adds "(1)", "(2)"... when two names clash.
private val takeoutVideosSuffix = Regex("""[\s_-]+videos(\s*\(\d+\))?$""", RegexOption.IGNORE_CASE)

internal fun String.withoutEnglishVideosSuffix(): String = replace(takeoutVideosSuffix, "")

private fun String.fallbackTakeoutPlaylistName(fallbackPlaylistName: String): String {
    val fallback =
        takeoutLeafStem()
            .withoutEnglishVideosSuffix()
            .trim { it.isWhitespace() || it == '-' || it == '_' }
    return fallback.validatedTakeoutName() ?: fallbackPlaylistName
}
