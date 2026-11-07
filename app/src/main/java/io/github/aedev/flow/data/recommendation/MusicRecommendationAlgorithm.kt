package io.github.aedev.flow.data.recommendation

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.LikedVideosRepository
import io.github.aedev.flow.data.music.PlaylistRepository
import io.github.aedev.flow.data.music.model.MusicArtist
import io.github.aedev.flow.data.music.model.MusicItemType
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.profile.ProfileFiles
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.models.AlbumItem
import io.github.aedev.flow.innertube.models.PlaylistItem
import io.github.aedev.flow.innertube.models.SongItem
import io.github.aedev.flow.innertube.models.WatchEndpoint
import io.github.aedev.flow.innertube.models.YTItem
import io.github.aedev.flow.innertube.pages.HomePage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import javax.inject.Inject
import javax.inject.Singleton

data class MusicSection(
    val title: String,
    val subtitle: String? = null,
    val label: String? = null,
    val thumbnailUrl: String? = null,
    val seedId: String? = null,
    val isArtistSeed: Boolean = false,
    val tracks: List<MusicTrack>,
)

/**
 * Advanced Music Recommendation Algorithm (FlowMusicAlgorithm)
 *
 * A hybrid recommendation engine that combines:
 * 1. YouTube Music's native Home Feed (Gold Standard)
 * 2. Collaborative Filtering (Seeds + Related)
 * 3. Global Trends & Charts
 * 4. User Library Signals (History, Favorites)
 */
@Singleton
class MusicRecommendationAlgorithm
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val playlistRepository: PlaylistRepository,
        private val likedVideosRepository: LikedVideosRepository,
        private val youTube: YouTube,
        private val cacheDao: io.github.aedev.flow.data.local.dao.CacheDao,
    ) {
        companion object {
            private const val TAG = "MusicRecAlgo"
            private const val CACHE_TTL_MS = 4 * 60 * 60 * 1000L
            private const val KEY_LAST_CACHE_TIME = "last_cache_time"
            private const val KEY_LAST_CONTINUATION = "last_continuation"
            private const val KEY_LAST_CACHE_LOCALE = "last_cache_region"
            private val cacheJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        }

        private val cachePrefs by lazy {
            context.getSharedPreferences(ProfileFiles.sharedPreferencesName(context, "music_home_cache_prefs"), Context.MODE_PRIVATE)
        }

        private fun currentLocaleKey(): String =
            io.github.aedev.flow.innertube.YouTube.locale
                .let { "${it.gl}|${it.hl}" }

        private fun isCacheLocaleCurrent(): Boolean = cachePrefs.getString(KEY_LAST_CACHE_LOCALE, null) == currentLocaleKey()

        /** True while the cached home is inside its TTL — callers may skip the network refresh. */
        fun isHomeCacheFresh(): Boolean =
            isCacheLocaleCurrent() &&
                System.currentTimeMillis() - cachePrefs.getLong(KEY_LAST_CACHE_TIME, 0L) < CACHE_TTL_MS

        suspend fun loadMusicHome(): Pair<List<MusicSection>, String?> =
            withContext(Dispatchers.IO) {
                val cachedSections = if (isCacheLocaleCurrent()) cacheDao.getMusicHomeSections().firstOrNull() else null
                if (cachedSections != null && cachedSections.isNotEmpty()) {
                    val musicSections =
                        cachedSections
                            .map { entity ->
                                MusicSection(
                                    title = entity.title,
                                    subtitle = entity.subtitle,
                                    tracks = deserializeTracks(entity.tracksJson),
                                )
                            }.filter { it.tracks.isNotEmpty() }
                    // An old-format cache deserializes to nothing — fall through to the network.
                    if (musicSections.isNotEmpty()) {
                        Log.d(TAG, "Loaded ${musicSections.size} sections from cache (fresh=${isHomeCacheFresh()})")
                        return@withContext musicSections to cachePrefs.getString(KEY_LAST_CONTINUATION, null)
                    }
                }

                val networkResult = fetchAndCacheHome()
                return@withContext networkResult
            }

        /** Network refresh, skipped entirely while the cache is fresh. Returns null when skipped. */
        suspend fun refreshMusicHomeIfStale(): Pair<List<MusicSection>, String?>? =
            withContext(Dispatchers.IO) {
                val hasCache = cacheDao.getMusicHomeSections().firstOrNull()?.isNotEmpty() == true
                if (hasCache && isHomeCacheFresh()) {
                    Log.d(TAG, "Music home cache fresh — skipping network refresh")
                    null
                } else {
                    fetchAndCacheHome()
                }
            }

        /**
         * Get home chips from cache
         */
        suspend fun getHomeChips(): List<HomePage.Chip> =
            withContext(Dispatchers.IO) {
                val cachedChips = cacheDao.getMusicHomeChips().firstOrNull() ?: emptyList()
                cachedChips.map { entity ->
                    HomePage.Chip(
                        title = entity.title,
                        endpoint =
                            if (entity.browseId !=
                                null
                            ) {
                                io.github.aedev.flow.innertube.models
                                    .BrowseEndpoint(entity.browseId, entity.params)
                            } else {
                                null
                            },
                        deselectEndPoint =
                            if (entity.deselectBrowseId !=
                                null
                            ) {
                                io.github.aedev.flow.innertube.models
                                    .BrowseEndpoint(entity.deselectBrowseId, entity.deselectParams)
                            } else {
                                null
                            },
                    )
                }
            }

        /**
         * Force refresh content from network and update cache
         */
        suspend fun refreshMusicHome(): Pair<List<MusicSection>, String?> =
            withContext(Dispatchers.IO) {
                fetchAndCacheHome()
            }

        /**
         * Load more home content (pagination)
         */
        suspend fun loadHomeContinuation(continuation: String): Pair<List<MusicSection>, String?> =
            withContext(Dispatchers.IO) {
                try {
                    val homePage = youTube.home(continuation = continuation).getOrNull()
                    if (homePage != null) {
                        val sections = parseHomeSections(homePage)
                        return@withContext sections to homePage.continuation
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading home continuation", e)
                }
                return@withContext emptyList<MusicSection>() to null
            }

        /** One chip's own page. Throws on failure so the caller can offer a retry. */
        suspend fun loadHomeChipPage(endpoint: io.github.aedev.flow.innertube.models.BrowseEndpoint): Pair<List<MusicSection>, String?> {
            val homePage = youTube.home(browseId = endpoint.browseId, params = endpoint.params).getOrThrow()
            return parseHomeSections(homePage) to homePage.continuation
        }

        private suspend fun fetchAndCacheHome(): Pair<List<MusicSection>, String?> {
            try {
                val homePage = youTube.home().getOrNull()
                if (homePage != null) {
                    val sections = parseHomeSections(homePage)

                    // Cache them
                    val entities =
                        sections.mapIndexed { index, section ->
                            io.github.aedev.flow.data.local.entity.MusicHomeCacheEntity(
                                sectionId = "section_$index",
                                title = section.title,
                                subtitle = section.subtitle,
                                tracksJson = serializeTracks(section.tracks),
                                orderBy = index,
                            )
                        }
                    cacheDao.clearMusicHomeCache()
                    cacheDao.insertMusicHomeSections(entities)
                    cachePrefs
                        .edit()
                        .putLong(KEY_LAST_CACHE_TIME, System.currentTimeMillis())
                        .putString(KEY_LAST_CONTINUATION, homePage.continuation)
                        .putString(KEY_LAST_CACHE_LOCALE, currentLocaleKey())
                        .apply()

                    homePage.chips?.let { chips ->
                        val chipEntities =
                            chips.mapIndexed { index, chip ->
                                io.github.aedev.flow.data.local.entity.MusicHomeChipEntity(
                                    title = chip.title,
                                    browseId = chip.endpoint?.browseId,
                                    params = chip.endpoint?.params,
                                    deselectBrowseId = chip.deselectEndPoint?.browseId,
                                    deselectParams = chip.deselectEndPoint?.params,
                                    orderBy = index,
                                )
                            }
                        cacheDao.clearMusicHomeChips()
                        cacheDao.insertMusicHomeChips(chipEntities)
                    }

                    return sections to homePage.continuation
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching Music Home", e)
            }
            return emptyList<MusicSection>() to null
        }

        // Lossless full-track serialization: the old hand-rolled format dropped artist
        // browseIds, albumId, and itemType, so every cached item round-tripped as a bare SONG.
        private fun serializeTracks(tracks: List<MusicTrack>): String = cacheJson.encodeToString(tracks)

        private fun deserializeTracks(json: String): List<MusicTrack> =
            try {
                cacheJson.decodeFromString(json)
            } catch (e: Exception) {
                // Old-format (or corrupt) cache rows: treat as empty so callers refetch.
                Log.w(TAG, "Cache entry unreadable, will refetch: ${e.message}")
                emptyList()
            }

        /**
         * Generate personalized music recommendations (Quick Picks / For You).
         * Tries to use the official "Quick Picks" or "Start Radio" from Home first.
         * Falls back to internal algorithm if Home is unavailable.
         */
        suspend fun getRecommendations(limit: Int = 30): List<MusicTrack> =
            withContext(Dispatchers.IO) {
                // 1. Try to get from Home Page "Quick Picks" or similar
                try {
                    val homePage = youTube.home().getOrNull()
                    if (homePage != null) {
                        val quickPicks =
                            homePage.sections.find {
                                it.title.contains("Quick picks", true) ||
                                    it.title.contains("Start radio", true) ||
                                    it.title.contains("Mixed for you", true) ||
                                    it.title.contains("Recommended", true) ||
                                    it.title.contains("Listen again", true)
                            }

                        if (quickPicks != null) {
                            val tracks =
                                quickPicks.items
                                    .filterIsInstance<SongItem>()
                                    .filterNot { it.isVideoSong }
                                    .map { mapSongItem(it) }
                            if (tracks.isNotEmpty()) {
                                Log.d(TAG, "Using Home Page Quick Picks: ${tracks.size}")
                                return@withContext tracks.take(limit)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching Home for recommendations", e)
                }

                // 2. Fallback: Internal Hybrid Algorithm
                return@withContext generateFallbackRecommendations(limit)
            }

        private suspend fun generateFallbackRecommendations(limit: Int): List<MusicTrack> =
            coroutineScope {
                val candidates = mutableListOf<MusicTrack>()
                val seenIds = mutableSetOf<String>()

                // Gather User Signals
                val favorites =
                    likedVideosRepository.getAllLikedVideos().firstOrNull()?.map {
                        MusicTrack(
                            videoId = it.videoId,
                            title = it.title,
                            artist = it.channelName,
                            thumbnailUrl = it.thumbnail,
                            duration = 0,
                            channelId = "",
                            views = 0L,
                        )
                    } ?: emptyList()

                val history = playlistRepository.history.firstOrNull() ?: emptyList()

                // Seeds: Mix of history and favorites
                val seeds = (history.take(5) + favorites.take(5)).shuffled().take(4)

                val deferreds = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()

                // A. Related to Seeds
                seeds.forEach { seed ->
                    deferreds.add(
                        async {
                            try {
                                val nextResult = youTube.next(WatchEndpoint(videoId = seed.videoId)).getOrNull()
                                val relatedEndpoint = nextResult?.relatedEndpoint
                                if (relatedEndpoint != null) {
                                    val related = youTube.related(relatedEndpoint).getOrNull()
                                    related?.songs?.forEach { song ->
                                        addCandidate(song, candidates, seenIds)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error fetching related for ${seed.title}", e)
                            }
                            Unit
                        },
                    )
                }

                // B. Charts (Trending)
                deferreds.add(
                    async {
                        try {
                            val charts = youTube.getChartsPage(youTube.locale.gl).getOrNull()
                            charts
                                ?.sections
                                ?.filter { it.chartType == io.github.aedev.flow.innertube.pages.ChartsPage.ChartType.SONGS }
                                ?.forEach { section ->
                                    section.items.filterIsInstance<SongItem>().forEach { song ->
                                        addCandidate(song, candidates, seenIds)
                                    }
                                }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error fetching charts", e)
                        }
                        Unit
                    },
                )

                deferreds.awaitAll()

                // Scoring & Ranking (Simple version: Shuffle for now, but could be enhanced)
                val finalRecommendations = candidates.shuffled().take(limit)
                Log.d(TAG, "Generated ${finalRecommendations.size} fallback recommendations")
                return@coroutineScope finalRecommendations
            }

        fun parseHomeSections(homePage: HomePage): List<MusicSection> =
            homePage.sections.mapNotNull { section ->
                // Broaden filter to include Songs, Albums, Playlists
                val tracks = section.items.mapNotNull { mapYTItem(it) }

                if (tracks.isNotEmpty()) {
                    MusicSection(
                        title = section.title,
                        subtitle = section.label,
                        tracks = tracks,
                    )
                } else {
                    null
                }
            }

        private fun mapYTItem(item: YTItem): MusicTrack? =
            when (item) {
                is SongItem -> {
                    mapSongItem(item)
                }

                is AlbumItem -> {
                    MusicTrack(
                        videoId = item.id,
                        title = item.title,
                        artist = item.artists?.joinToString(", ") { it.name } ?: "",
                        thumbnailUrl = item.thumbnail,
                        duration = 0,
                        // Structured attribution so "don't recommend"/"not interested"
                        // feedback hides an artist's album cards, not just their songs.
                        channelId = item.artists?.firstOrNull()?.id ?: "",
                        views = 0L,
                        album = "Album",
                        isExplicit = item.explicit,
                        itemType = MusicItemType.ALBUM,
                        artists = item.artists?.map { MusicArtist(name = it.name, id = it.id) } ?: emptyList(),
                    )
                }

                is PlaylistItem -> {
                    MusicTrack(
                        videoId = item.id, // Playlist ID
                        title = item.title,
                        artist = item.author?.name ?: "",
                        thumbnailUrl = item.thumbnail ?: "",
                        duration = 0,
                        channelId = "",
                        views = 0L,
                        album = "Playlist",
                        itemType = MusicItemType.PLAYLIST,
                    )
                }

                else -> {
                    null
                }
            }

        private fun addCandidate(
            song: SongItem,
            candidates: MutableList<MusicTrack>,
            seenIds: MutableSet<String>,
        ) {
            synchronized(seenIds) {
                if (song.id !in seenIds && !song.isVideoSong) {
                    seenIds.add(song.id)
                    candidates.add(mapSongItem(song))
                }
            }
        }

        private fun mapSongItem(song: SongItem): MusicTrack =
            MusicTrack(
                videoId = song.id,
                title = song.title,
                artist = song.artists.joinToString(", ") { it.name },
                thumbnailUrl = song.thumbnail,
                duration = song.duration ?: 0,
                channelId = song.artists.firstOrNull()?.id ?: "",
                views = parseViewCount(song.viewCountText),
                album = song.album?.name ?: "",
                isExplicit = song.explicit,
                isVideoSong = song.isVideoSong,
                albumId = song.album?.id,
                artists = song.artists.map { MusicArtist(name = it.name, id = it.id) },
            )

        suspend fun getGenreContent(genre: String): List<MusicTrack> =
            withContext(Dispatchers.IO) {
                try {
                    // Search for the genre to get relevant songs
                    val searchResults = youTube.search(query = "$genre music", filter = YouTube.SearchFilter.FILTER_SONG).getOrNull()
                    if (searchResults != null) {
                        return@withContext searchResults.items
                            .filterIsInstance<SongItem>()
                            .filterNot { it.isVideoSong }
                            .map { mapSongItem(it) }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching genre content for $genre", e)
                }
                return@withContext emptyList()
            }

        private fun parseViewCount(text: String?): Long {
            if (text.isNullOrEmpty()) return 0L
            // Remove "views" and commas
            val cleanText =
                text
                    .replace(" views", "", ignoreCase = true)
                    .replace(" view", "", ignoreCase = true)
                    .replace(",", "")
                    .trim()

            return try {
                when {
                    cleanText.endsWith("M", ignoreCase = true) -> {
                        (cleanText.dropLast(1).toDouble() * 1_000_000).toLong()
                    }

                    cleanText.endsWith("K", ignoreCase = true) -> {
                        (cleanText.dropLast(1).toDouble() * 1_000).toLong()
                    }

                    cleanText.endsWith("B", ignoreCase = true) -> {
                        (cleanText.dropLast(1).toDouble() * 1_000_000_000).toLong()
                    }

                    else -> {
                        cleanText.toLongOrNull() ?: 0L
                    }
                }
            } catch (e: Exception) {
                0L
            }
        }
    }
