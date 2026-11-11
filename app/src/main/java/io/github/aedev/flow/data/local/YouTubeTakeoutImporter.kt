package io.github.aedev.flow.data.local

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.entity.PlaylistEntity
import io.github.aedev.flow.data.local.entity.PlaylistVideoCrossRef
import io.github.aedev.flow.data.local.entity.VideoEntity
import io.github.aedev.flow.data.recommendation.FlowNeuroEngine
import io.github.aedev.flow.di.ProfileDb
import io.github.aedev.flow.utils.ThumbnailUrlResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.SerializationException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream

private const val ENGLISH_TAKEOUT_WATCH_HISTORY = "history/watch-history.html"
private const val TAKEOUT_PLAYLIST_ID_PREFIX = "yt_takeout_"
private const val MUSIC_LIBRARY_PLAYLIST_ID = "yt_takeout_music_library"
private const val NEURO_CANDIDATE_LIMIT = 800
private const val AVATAR_FETCHES = 5
private const val SUBSCRIPTION_BATCH = 25
private const val WATCH_BATCH = 500

/**
 * The all-in-one Google Takeout import. Takes every archive of one export, since Google splits a
 * large export and can put My Activity in a different part, reads each in a single pass, and saves
 * what it found once all are read. Files are recognised by their contents, not their names, which
 * Takeout translates into the account's language.
 */
internal class YouTubeTakeoutImporter(
    private val context: Context,
    @ProfileDb private val database: AppDatabase,
    private val viewHistory: ViewHistory,
    private val searchHistory: SearchHistoryRepository,
    private val subscriptions: SubscriptionRepository,
    private val saveLikes: suspend (List<TakeoutLike>) -> Int,
    private val channelAvatar: suspend (String) -> String,
    private val learnFromHistory: suspend (Collection<VideoHistoryEntry>) -> Unit,
) {
    private class Found {
        val subscriptions = mutableListOf<YouTubeTakeoutSubscription>()
        val playlistsByDirectory = mutableMapOf<String, MutableList<TakeoutPlaylistInfo>>()
        val playlistVideos = mutableMapOf<String, List<String>>()
        val librarySongs = mutableListOf<TakeoutLibrarySong>()
        var likes: List<TakeoutLike>? = null
        val searches = mutableListOf<TakeoutSearch>()
        var watches = 0
        val learnable = LinkedHashMap<String, VideoHistoryEntry>()
        val musicVideoIds = HashSet<String>()
        val watchedVideoIds = HashSet<String>()
        val adVideoIds = HashSet<String>()
    }

    suspend fun import(
        uris: List<Uri>,
        onProgress: ((label: String, current: Int, total: Int) -> Unit)? = null,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val found = Found()
                val budget = YouTubeTakeoutCsvBudget()
                for (uri in uris) {
                    context.contentResolver.openInputStream(uri)?.use { raw -> readArchive(raw, found, budget, onProgress) }
                        ?: return@withContext Result.failure(Exception("Could not open file"))
                }

                val subscriptionsImported = saveSubscriptions(found.subscriptions, onProgress)
                // The library first: its rows carry titles, which a playlist's bare video ids would otherwise claim.
                val librarySongsImported = saveMusicLibrary(found.librarySongs)
                found.librarySongs.mapTo(found.musicVideoIds) { it.videoId }
                found.likes
                    .orEmpty()
                    .filter { it.isMusic }
                    .mapTo(found.musicVideoIds) { it.videoId }
                val playlists = savePlaylists(found)
                val likesImported = found.likes?.let { saveLikes(it) } ?: 0
                val searchesImported = saveSearches(found.searches)
                val adsRemoved = removeImportedAds(found)

                val counts =
                    listOf(
                        subscriptionsImported,
                        found.watches,
                        playlists.count,
                        librarySongsImported,
                        likesImported,
                        searchesImported,
                        adsRemoved,
                    )
                if (counts.all { it == 0 }) return@withContext Result.failure(Exception("no_content"))
                if (found.watches > 0) runCatching { learnFromHistory(found.learnable.values) }

                Result.success(
                    buildList {
                        if (subscriptionsImported > 0) add(plural(R.plurals.import_takeout_part_subscriptions, subscriptionsImported))
                        if (found.watches > 0) add(plural(R.plurals.import_takeout_part_history, found.watches))
                        if (playlists.count > 0) {
                            add(
                                context.resources.getQuantityString(
                                    R.plurals.import_takeout_part_playlists,
                                    playlists.count,
                                    playlists.count,
                                    playlists.videos,
                                ),
                            )
                        }
                        if (playlists.music > 0) add(plural(R.plurals.import_takeout_part_music_playlists, playlists.music))
                        if (librarySongsImported > 0) add(plural(R.plurals.import_takeout_part_music_library, librarySongsImported))
                        if (likesImported > 0) add(context.getString(R.string.import_takeout_part_likes, likesImported))
                        if (searchesImported > 0) add(plural(R.plurals.import_takeout_part_searches, searchesImported))
                        if (adsRemoved > 0) add(plural(R.plurals.import_takeout_part_ads_removed, adsRemoved))
                    }.joinToString(", "),
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** A Takeout watch or search history HTML file on its own, as the single-file history import takes it; returns how many were saved. */
    suspend fun importHtmlHistory(input: InputStream): Int {
        val found = Found()
        val read =
            input.bufferedReader(Charsets.UTF_8).use { reader ->
                readTakeoutHtmlActivity(reader, System.currentTimeMillis(), requireActivityMarkup = false) { saveWatches(it, found) }
            }
        found.adVideoIds += read.adVideoIds
        removeImportedAds(found)
        if (found.watches > 0) runCatching { learnFromHistory(found.learnable.values) }
        return found.watches + saveSearches(read.searches)
    }

    private suspend fun readArchive(
        raw: InputStream,
        found: Found,
        budget: YouTubeTakeoutCsvBudget,
        onProgress: ((String, Int, Int) -> Unit)?,
    ) {
        ZipInputStream(raw.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                val english = name.endsWith(ENGLISH_TAKEOUT_WATCH_HISTORY, ignoreCase = true)
                when {
                    english || isYouTubeTakeoutHtmlEntry(name) -> {
                        onProgress?.invoke(context.getString(R.string.import_takeout_step_history), 0, 0)
                        // Each file is stamped below the last, so two history files never share a time.
                        val start = System.currentTimeMillis() - found.watches - found.searches.size
                        val read =
                            readTakeoutHtmlActivity(zip.bufferedReader(Charsets.UTF_8), start, requireActivityMarkup = !english) {
                                saveWatches(it, found)
                            }
                        found.searches += read.searches
                        found.adVideoIds += read.adVideoIds
                    }

                    !entry.isDirectory && isYouTubeTakeoutJsonEntry(name) -> {
                        onProgress?.invoke(context.getString(R.string.import_takeout_step_history), 0, 0)
                        readActivity(zip, keepWatches = true)?.let { activity ->
                            activity.watches.chunked(WATCH_BATCH).forEach { saveWatches(it, found) }
                            found.searches += activity.searches
                        }
                    }

                    // My Activity repeats the watch history, which the YouTube folder already gave.
                    !entry.isDirectory && isMyActivityYouTubeEntry(name) -> {
                        onProgress?.invoke(context.getString(R.string.import_takeout_step_activity), 0, 0)
                        readActivity(zip, keepWatches = false)?.let { activity ->
                            if (found.likes == null) found.likes = activity.likes.likes
                            found.searches += activity.searches
                        }
                    }

                    !entry.isDirectory && isYouTubeTakeoutCsvEntry(name) -> {
                        budget.startEntry()
                        when (val content = readYouTubeTakeoutCsv(zip.bufferedReader(Charsets.UTF_8), budget)) {
                            is YouTubeTakeoutCsvContent.Subscriptions -> {
                                onProgress?.invoke(context.getString(R.string.import_takeout_step_subscriptions), 0, 0)
                                found.subscriptions += content.rows
                            }

                            is YouTubeTakeoutCsvContent.PlaylistVideos -> {
                                found.playlistVideos[name] = content.videoIds
                            }

                            is YouTubeTakeoutCsvContent.PlaylistMetadata -> {
                                found.playlistsByDirectory.getOrPut(name.takeoutParentPath()) { mutableListOf() } += content.playlists
                            }

                            is YouTubeTakeoutCsvContent.MusicLibrarySongs -> {
                                found.librarySongs += content.songs
                            }

                            YouTubeTakeoutCsvContent.Unsupported -> {
                                Unit
                            }
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    /** Any other JSON the archive holds is not an activity file; it is skipped, not a failed import. */
    private fun readActivity(
        input: InputStream,
        keepWatches: Boolean,
    ): TakeoutActivity? =
        try {
            readTakeoutActivity(input, keepWatches = keepWatches)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    /** Video and music searches go to their own lists; returns how many were kept. */
    private suspend fun saveSearches(searches: List<TakeoutSearch>): Int =
        searches
            .groupBy { if (it.isMusic) SearchHistoryScope.MUSIC else SearchHistoryScope.VIDEO }
            .entries
            .sumOf { (scope, inScope) ->
                searchHistory.importSearches(inScope.map { SearchHistoryItem(query = it.query, timestamp = it.searchedAt) }, scope)
            }

    private suspend fun saveWatches(
        watches: List<TakeoutWatch>,
        found: Found,
    ) {
        if (watches.isEmpty()) return
        val entries = watches.map { it.toHistoryEntry() }
        viewHistory.bulkSaveHistoryEntries(entries)
        watches.filter { it.isMusic }.mapTo(found.musicVideoIds) { it.videoId }
        watches.mapTo(found.watchedVideoIds) { it.videoId }
        entries.forEach { entry ->
            if (found.learnable.size < NEURO_CANDIDATE_LIMIT && entry.title.isNotBlank()) found.learnable.putIfAbsent(entry.videoId, entry)
        }
        found.watches += entries.size
        yield()
    }

    /**
     * An earlier import, before ads were told apart, saved them as watched (#1944). Rows for the
     * ads this export lists go, unless they were played in Flow since or the export also lists the
     * video as a real watch.
     */
    private suspend fun removeImportedAds(found: Found): Int {
        val ads = found.adVideoIds - found.watchedVideoIds
        return if (ads.isEmpty()) 0 else viewHistory.removeUnplayedEntries(ads)
    }

    private suspend fun saveSubscriptions(
        rows: List<YouTubeTakeoutSubscription>,
        onProgress: ((String, Int, Int) -> Unit)?,
    ): Int {
        if (rows.isEmpty()) return 0
        val label = context.getString(R.string.import_takeout_step_subscriptions)
        onProgress?.invoke(label, 0, rows.size)
        val semaphore = Semaphore(AVATAR_FETCHES)
        val completed = AtomicInteger(0)
        val imported = mutableListOf<ChannelSubscription>()
        supervisorScope {
            rows.chunked(SUBSCRIPTION_BATCH).forEach { batch ->
                imported +=
                    batch
                        .map { sub ->
                            async(Dispatchers.IO) {
                                semaphore.withPermit {
                                    val avatar = runCatching { channelAvatar(sub.channelId) }.getOrDefault("")
                                    onProgress?.invoke(label, completed.incrementAndGet(), rows.size)
                                    ChannelSubscription(
                                        channelId = sub.channelId,
                                        channelName = sub.channelName,
                                        channelThumbnail = avatar,
                                        subscribedAt = System.currentTimeMillis(),
                                    )
                                }
                            }
                        }.awaitAll()
            }
        }
        subscriptions.subscribeAll(imported)
        val names = rows.map { it.channelName }.filter { it.isNotEmpty() }
        if (names.isNotEmpty()) runCatching { FlowNeuroEngine.bootstrapFromSubscriptions(context, names) }
        return imported.size
    }

    private data class SavedPlaylists(
        val count: Int,
        val videos: Int,
        val music: Int,
    )

    /** Returns how many playlists were saved, how many videos they hold and how many went to Music. */
    private suspend fun savePlaylists(found: Found): SavedPlaylists {
        validateYouTubeTakeoutPlaylistCount(
            videoFileCount = found.playlistVideos.size,
            metadataTitleCount = found.playlistsByDirectory.values.sumOf { it.size },
        )
        val fallbackName = context.getString(R.string.imported_playlist_fallback)
        var playlists = 0
        var videos = 0
        var music = 0
        found.playlistVideos.keys
            .groupBy { it.takeoutParentPath() }
            .forEach { (directory, files) ->
                val infos = found.playlistsByDirectory[directory].orEmpty()
                val unclaimed = infos.toMutableList()
                resolveYouTubeTakeoutPlaylistNames(files, infos.map { it.title }, fallbackName).forEach { (file, name) ->
                    val info = unclaimed.firstOrNull { it.title == name }?.also(unclaimed::remove)
                    val videoIds = found.playlistVideos.getValue(file)
                    if (savePlaylist(file, name, info, videoIds, found.musicVideoIds)) music++
                    playlists++
                    videos += videoIds.size
                }
            }
        return SavedPlaylists(playlists, videos, music)
    }

    /**
     * Saved under its YouTube id, so importing the same export again fills the same playlist instead
     * of adding a copy, and dated when YouTube made it, so "Newest" orders playlists the way YouTube
     * does. A file `playlists.csv` doesn't describe is keyed by its own name. Returns whether it went to
     * the music library; one an earlier import put in Videos moves there too, but never back out.
     */
    private suspend fun savePlaylist(
        file: String,
        name: String,
        info: TakeoutPlaylistInfo?,
        videoIds: List<String>,
        knownMusic: Set<String>,
    ): Boolean {
        val isWatchLater = name.equals("watch later", ignoreCase = true)
        val isMusic = !isWatchLater && isTakeoutMusicPlaylist(videoIds, knownMusic)
        val playlistId =
            when {
                isWatchLater -> PlaylistRepository.WATCH_LATER_ID
                info != null -> TAKEOUT_PLAYLIST_ID_PREFIX + info.id
                else -> TAKEOUT_PLAYLIST_ID_PREFIX + file.substringAfterLast('/').hashCode().toUInt()
            }
        val entity =
            PlaylistEntity(
                id = playlistId,
                name = if (isWatchLater) "Watch Later" else name,
                description = context.getString(R.string.imported_from_google_takeout),
                thumbnailUrl = ThumbnailUrlResolver.buildHighQualityYoutubeThumbnail(videoIds.first()),
                isPrivate = isWatchLater,
                createdAt = info?.createdAt ?: System.currentTimeMillis(),
                isMusic = isMusic,
                isUserCreated = true,
            )
        fillPlaylist(entity, videoIds.map { videoEntity(it, title = "", artists = "", isMusic = it in knownMusic) })
        if (isMusic) database.playlistDao().setPlaylistIsMusic(playlistId, isMusic = true)
        return isMusic
    }

    /** The songs saved to the YouTube Music library, as one music playlist that a second import fills again. */
    private suspend fun saveMusicLibrary(songs: List<TakeoutLibrarySong>): Int {
        val unique = songs.distinctBy { it.videoId }
        if (unique.isEmpty()) return 0
        fillPlaylist(
            PlaylistEntity(
                id = MUSIC_LIBRARY_PLAYLIST_ID,
                name = context.getString(R.string.import_takeout_music_library_name),
                description = context.getString(R.string.imported_from_google_takeout),
                thumbnailUrl = ThumbnailUrlResolver.buildHighQualityYoutubeThumbnail(unique.first().videoId),
                isPrivate = false,
                createdAt = System.currentTimeMillis(),
                isMusic = true,
                isUserCreated = true,
            ),
            unique.map { videoEntity(it.videoId, it.title, it.artists, isMusic = true) },
        )
        return unique.size
    }

    private fun plural(
        res: Int,
        count: Int,
    ): String = context.resources.getQuantityString(res, count, count)

    /** Creates [playlist] if it is new, then appends the videos it does not hold yet after its last one. */
    private suspend fun fillPlaylist(
        playlist: PlaylistEntity,
        videos: List<VideoEntity>,
    ) {
        if (videos.isEmpty()) return
        database.withTransaction {
            val dao = database.playlistDao()
            if (dao.getPlaylist(playlist.id) == null) dao.insertPlaylist(playlist)
            val held = dao.getVideoIdsInPlaylist(playlist.id).toHashSet()
            var position = (dao.getMaxPlaylistPosition(playlist.id) ?: -1L) + 1L
            videos.forEach { video ->
                database.videoDao().insertVideoOrIgnore(video)
                if (!held.add(video.id)) return@forEach
                dao.insertPlaylistVideoCrossRef(PlaylistVideoCrossRef(playlistId = playlist.id, videoId = video.id, position = position++))
            }
        }
    }
}

private fun TakeoutWatch.toHistoryEntry() =
    VideoHistoryEntry(
        videoId = videoId,
        position = 0L,
        duration = 0L,
        timestamp = watchedAt,
        title = title,
        thumbnailUrl = ThumbnailUrlResolver.buildHighQualityYoutubeThumbnail(videoId),
        channelName = channelName,
        channelId = channelId,
        isMusic = isMusic,
    )

private fun videoEntity(
    videoId: String,
    title: String,
    artists: String,
    isMusic: Boolean,
) = VideoEntity(
    id = videoId,
    title = title,
    channelName = artists,
    channelId = "",
    thumbnailUrl = ThumbnailUrlResolver.buildHighQualityYoutubeThumbnail(videoId),
    duration = 0,
    viewCount = 0L,
    uploadDate = "",
    description = "",
    channelThumbnailUrl = "",
    isMusic = isMusic,
)
