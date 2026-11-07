package io.github.aedev.flow.ui.screens.music

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.LikedVideosRepository
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.music.DownloadManager
import io.github.aedev.flow.data.music.MusicCache
import io.github.aedev.flow.data.music.YouTubeMusicService
import io.github.aedev.flow.data.music.model.ArtistDetails
import io.github.aedev.flow.data.music.model.CommunityMusicPlaylist
import io.github.aedev.flow.data.music.model.DailyDiscoverItem
import io.github.aedev.flow.data.music.model.MusicItemType
import io.github.aedev.flow.data.music.model.MusicPlaylist
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.music.model.RelatedMusic
import io.github.aedev.flow.data.music.model.audioMusicOnly
import io.github.aedev.flow.data.music.model.isAudioMusicCandidate
import io.github.aedev.flow.data.newmusic.InnertubeMusicService
import io.github.aedev.flow.data.recommendation.MusicRecommendationAlgorithm
import io.github.aedev.flow.data.recommendation.MusicSection
import io.github.aedev.flow.data.recommendation.music.MusicArtistInsights
import io.github.aedev.flow.data.recommendation.music.MusicQuickPicks
import io.github.aedev.flow.data.recommendation.music.MusicTimeBucket
import io.github.aedev.flow.data.recommendation.music.graph.MusicGraphStore
import io.github.aedev.flow.data.recommendation.music.musicArtistKey
import io.github.aedev.flow.data.recommendation.music.onRepeatShelf
import io.github.aedev.flow.data.recommendation.music.primaryArtistKey
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.models.BrowseEndpoint
import io.github.aedev.flow.innertube.models.SongItem
import io.github.aedev.flow.innertube.pages.ArtistItemsPage
import io.github.aedev.flow.innertube.pages.HomePage
import io.github.aedev.flow.innertube.pages.MoodAndGenres
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlin.random.Random

@HiltViewModel
class MusicViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val musicRecommendationAlgorithm: MusicRecommendationAlgorithm,
        private val subscriptionRepository: io.github.aedev.flow.data.local.SubscriptionRepository,
        private val playlistRepository: io.github.aedev.flow.data.music.PlaylistRepository,
        private val downloadManager: DownloadManager,
        private val musicBrain: io.github.aedev.flow.data.recommendation.music.MusicBrainEngine,
        private val playerPreferences: PlayerPreferences,
        private val musicGraph: MusicGraphStore,
        private val dailyMixStore: io.github.aedev.flow.data.recommendation.music.DailyMixStore,
    ) : ViewModel() {
        companion object {
            /** Route prefix for synthesized Daily Mix playlist pages. */
            const val DAILY_MIX_ID_PREFIX = "daily_mix_"
            private const val SESSION_CACHE_LIMIT = 48
            private const val SECONDARY_CONTENT_START_CAP_MS = 1_500L
            private const val COMMUNITY_ARTIST_SEEDS = 3
            private const val COMMUNITY_TRACK_SEEDS = 2
            private const val COMMUNITY_PLAYLIST_COUNT = 6
            private const val COMMUNITY_PREVIEW_TRACKS = 10
            private const val DEEP_CUT_ALBUM_FETCHES = 2
            private const val DEEP_CUT_ALBUM_POOL = 20
            private const val DEEP_CUT_LIMIT = 12
            private const val ARTISTS_FOR_YOU_LIMIT = 10
            private const val ARTISTS_FOR_YOU_SEEDS = 12
        }

        private val _uiState = MutableStateFlow(MusicUiState())

        // WhileSubscribed (not Eagerly) is load-bearing for battery: it makes
        // _uiState.subscriptionCount reflect real UI visibility, which gates the
        // per-track shelf recomposition below. Hidden artists are combined here
        // so feedback removes an artist from every shelf reactively.
        private val homeChipFeed =
            MusicHomeChipFeed(
                scope = viewModelScope,
                dispatcher = PerformanceDispatcher.networkIO,
                loadPage = { chip -> musicRecommendationAlgorithm.loadHomeChipPage(requireNotNull(chip.endpoint)) },
                loadMorePage = musicRecommendationAlgorithm::loadHomeContinuation,
                prepareSections = { sections -> sections.rankSongShelves { musicBrain.rankTracks(it, "quick_picks") } },
            )

        val uiState: StateFlow<MusicUiState> =
            combine(_uiState, homeChipFeed.page, musicBrain.hiddenArtists) { state, chipPage, hidden ->
                state.copy(homeChipPage = chipPage).withHiddenArtists(hidden).withUniqueLazyContent()
            }.flowOn(PerformanceDispatcher.parsing)
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = _uiState.value.withUniqueLazyContent(),
                )

        private fun isUiVisible(): Boolean = _uiState.subscriptionCount.value > 0

        init {
            loadMusicContent()

            viewModelScope.launch(PerformanceDispatcher.parsing) {
                downloadManager.downloadedTracks.collect { tracks ->
                    _uiState.update { state ->
                        state.copy(downloadedTrackIds = tracks.map { it.track.videoId }.toSet())
                    }
                }
            }

            // Track changes only recompose the shelves while the Music UI is on
            // screen; background playback (screen off, other tabs) marks them
            // stale instead — otherwise endless radio would trigger a full
            // network + ranking pass every few minutes all night.
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                var lastTrackId: String? = EnhancedMusicPlayerManager.currentTrack.value?.videoId
                EnhancedMusicPlayerManager.currentTrack.collectLatest { activeTrack ->
                    if (activeTrack != null && !activeTrack.videoId.isNullOrBlank()) {
                        if (activeTrack.videoId != lastTrackId) {
                            lastTrackId = activeTrack.videoId
                            if (isUiVisible()) {
                                rebuildQuickPicks(activeTrack)
                                refreshLocalShelves()
                            } else {
                                shelvesStale = true
                            }
                        }
                    }
                }
            }

            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                _uiState.subscriptionCount.collect { count ->
                    if (count > 0 && homeStale) {
                        homeStale = false
                        shelvesStale = false
                        refresh()
                    } else if (count > 0 && shelvesStale) {
                        shelvesStale = false
                        rebuildQuickPicks(EnhancedMusicPlayerManager.currentTrack.value)
                        refreshLocalShelves()
                    }
                }
            }

            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                combine(playerPreferences.contentLanguage, playerPreferences.trendingRegion) { language, region -> language to region }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect {
                        homeChipFeed.reset()
                        if (isUiVisible()) refresh() else homeStale = true
                    }
            }

            // Speed dial ranked by the comfort surface, so the tiles are the
            // truest "most yours" rather than raw shelf concatenation order.
            // Writing speedDialTracks re-emits _uiState, but the source triple is
            // unchanged then, so distinctUntilChanged breaks the loop.
            viewModelScope.launch(PerformanceDispatcher.parsing) {
                _uiState
                    .map { Triple(it.history, it.forYouTracks, it.listenAgain) }
                    .distinctUntilChanged()
                    .collectLatest { (history, forYou, listenAgain) ->
                        val pool = (history + forYou + listenAgain).audioMusicOnly().take(40)
                        if (pool.isEmpty()) return@collectLatest
                        val ranked = musicBrain.rankTracks(pool, "heavy_rotation").take(26)
                        if (ranked.isNotEmpty()) {
                            _uiState.update { it.copy(speedDialTracks = ranked) }
                        }
                    }
            }
        }

        @Volatile
        private var shelvesStale = false

        @Volatile
        private var homeStale = false

        /** True once the multi-lane composer has produced a shelf — YT-home and history fallbacks must not overwrite it. */
        @Volatile
        private var quickPicksComposed = false

        /**
         * Desktop-style Quick Picks: one related lane per seed (current track +
         * distinct-artist history) ranked on the comfort surface, plus a charts
         * discovery lane, round-robin interleaved so fresh content always lands.
         */
        private suspend fun rebuildQuickPicks(current: MusicTrack?) {
            try {
                val history =
                    playlistRepository.history
                        .firstOrNull()
                        .orEmpty()
                        .audioMusicOnly()
                val favorites =
                    runCatching { playlistRepository.favorites.firstOrNull().orEmpty() }
                        .getOrDefault(emptyList())
                        .audioMusicOnly()
                // Liked tracks join the seed pool after history: recency leads, but
                // saved taste keeps seeding even when recent history is noisy.
                val seeds = MusicQuickPicks.selectSeeds(current, history + favorites)

                val (relatedLanes, artistResult) =
                    kotlinx.coroutines.coroutineScope {
                        val relatedJobs =
                            seeds.map { seed ->
                                async(PerformanceDispatcher.networkIO) { cachedRelatedLane(seed.videoId, seed.primaryArtistKey(), seed) }
                            }
                        val artistJob =
                            async(PerformanceDispatcher.networkIO) {
                                runCatching { buildArtistLanes() }
                                    .onFailure { Log.w("MusicViewModel", "Artist lanes failed: $it") }
                                    .getOrDefault(ArtistLanes(emptyList(), emptyList()))
                            }
                        relatedJobs.awaitAll() to artistJob.await()
                    }
                val artistLanesRaw = artistResult.lanes

                if (artistResult.albums.size >= 4) {
                    _uiState.update { state ->
                        val topAlbumIds = state.topAlbums.mapTo(HashSet()) { it.id }
                        state.copy(favoriteArtistAlbums = artistResult.albums.filterNot { it.id in topAlbumIds })
                    }
                }

                val personalizedLanes =
                    relatedLanes
                        .filter { it.isNotEmpty() }
                        .map { musicBrain.rankTracks(it, "quick_picks") }
                val artistLanes =
                    artistLanesRaw
                        .filter { it.isNotEmpty() }
                        .map { musicBrain.rankTracks(it, "similar") }
                val discoveryLane =
                    _uiState.value.trendingSongs
                        .audioMusicOnly()
                        .takeIf { it.isNotEmpty() }
                        ?.let { musicBrain.rankTracks(it, "discover") }

                val lanes = personalizedLanes + artistLanes + listOfNotNull(discoveryLane)
                if (lanes.isEmpty()) return

                // Charts get the smallest quota: taste-driven lanes fill the shelf,
                // discovery stays a garnish (regional charts must never dominate).
                val laneCaps =
                    buildList {
                        repeat(personalizedLanes.size + artistLanes.size) { add(Int.MAX_VALUE) }
                        if (discoveryLane != null) add(MusicQuickPicks.DISCOVERY_MAX_PICKS)
                    }

                val excluded = seeds.map { it.videoId }.toSet()
                val mixed = MusicQuickPicks.interleave(lanes, MusicQuickPicks.TARGET, excluded, laneCaps)
                Log.d(
                    "MusicViewModel",
                    "Quick Picks related=[${relatedLanes.joinToString { it.size.toString() }}] " +
                        "artist=[${artistLanesRaw.joinToString { it.size.toString() }}] " +
                        "charts=${discoveryLane.orEmpty().size} mixed=${mixed.size} " +
                        "relatedFromGraph=${relatedFromGraph.get()} relatedFromNetwork=${relatedFromNetwork.get()}",
                )
                if (mixed.size >= 4) {
                    quickPicksComposed = true
                    _uiState.update { it.copy(forYouTracks = mixed) }
                }
            } catch (e: Exception) {
                Log.e("MusicViewModel", "Error composing Quick Picks", e)
            }
        }

        private val artistDetailsCache = ConcurrentHashMap<String, Deferred<ArtistDetails?>>()
        private val relatedCache = ConcurrentHashMap<String, Deferred<RelatedMusic?>>()
        private val relatedFromGraph = AtomicInteger()
        private val relatedFromNetwork = AtomicInteger()

        private suspend fun <V> ConcurrentHashMap<String, Deferred<V?>>.fetchOnce(
            key: String,
            fetch: suspend () -> V?,
        ): V? {
            if (size >= SESSION_CACHE_LIMIT) clear()
            val deferred =
                computeIfAbsent(key) {
                    viewModelScope.async(PerformanceDispatcher.networkIO, start = CoroutineStart.LAZY) {
                        runCatching { fetch() }.getOrNull()
                    }
                }
            val value = deferred.await()
            if (value == null) remove(key, deferred)
            return value
        }

        private suspend fun cachedRelated(
            seedId: String,
            seedArtistKey: String? = null,
            seed: MusicTrack? = null,
        ): RelatedMusic? =
            relatedCache.fetchOnce(seedId) {
                val related =
                    musicGraph.relatedFor(seedId)?.also { relatedFromGraph.incrementAndGet() }
                        ?: InnertubeMusicService.getRelatedPage(seedId, audioOnly = true)?.also {
                            relatedFromNetwork.incrementAndGet()
                            musicGraph.recordRelated(seedId, seed, it)
                        }
                if (related != null && seedArtistKey != null && related.similarArtists.isNotEmpty()) {
                    musicBrain.recordArtistRelated(seedArtistKey, related.similarArtists.map { it.channelId })
                }
                related
            }

        private suspend fun cachedRelatedLane(
            seedId: String,
            seedArtistKey: String? = null,
            seed: MusicTrack? = null,
        ): List<MusicTrack> =
            cachedRelated(seedId, seedArtistKey, seed)
                ?.tracks
                ?.audioMusicOnly()
                ?.take(MusicQuickPicks.LANE_SIZE)
                .orEmpty()

        private suspend fun cachedArtistDetails(channelId: String): ArtistDetails? =
            artistDetailsCache.fetchOnce(channelId) {
                musicGraph.artistFor(channelId)
                    ?: InnertubeMusicService.fetchArtistDetails(channelId)?.also { musicGraph.recordArtist(it) }
            }

        /** Lanes for the Quick Picks composer plus the artists' own releases for the albums shelf. */
        private data class ArtistLanes(
            val lanes: List<List<MusicTrack>>,
            val albums: List<MusicPlaylist>,
        )

        /**
         * The artist-graph lanes: top tracks of the brain's strongest artists, plus
         * one lane drawn from their "fans also like" artists — recall the user's
         * taste has earned, independent of what happens to be in recent history.
         */
        private suspend fun buildArtistLanes(): ArtistLanes {
            val topArtists = musicBrain.topArtistKeys(MusicQuickPicks.ARTIST_LANE_COUNT)
            if (topArtists.isEmpty()) return ArtistLanes(emptyList(), emptyList())

            val lanes = ArrayList<List<MusicTrack>>()
            val relatedPerArtist = ArrayList<List<String>>()
            val releasesPerArtist = ArrayList<List<MusicPlaylist>>()
            kotlinx.coroutines.coroutineScope {
                topArtists
                    .map { key -> async(PerformanceDispatcher.networkIO) { key to cachedArtistDetails(key) } }
                    .awaitAll()
                    .forEach { (key, details) ->
                        if (details == null) return@forEach
                        details.topTracks
                            .audioMusicOnly()
                            .take(MusicQuickPicks.LANE_SIZE)
                            .takeIf { it.isNotEmpty() }
                            ?.let { lanes.add(it) }
                        // Artist pages list releases newest-first, so the head of
                        // each list is that artist's latest work.
                        (details.albums.take(2) + details.singles.take(2))
                            .filter { it.id.isNotBlank() }
                            .takeIf { it.isNotEmpty() }
                            ?.let { releasesPerArtist.add(it) }
                        val related = details.relatedArtists.mapNotNull { r -> r.channelId.takeIf { it.isNotBlank() } }
                        if (related.isNotEmpty()) {
                            musicBrain.recordArtistRelated(key, related)
                            relatedPerArtist.add(related)
                        }
                    }

                // One similar artist from each top artist's fans-also-like row in
                // turn, so the lane isn't a single artist's neighborhood.
                val fanKeys = LinkedHashSet<String>()
                var depth = 0
                while (fanKeys.size < MusicQuickPicks.SIMILAR_ARTIST_COUNT && depth < 10) {
                    var any = false
                    for (related in relatedPerArtist) {
                        val key = related.getOrNull(depth) ?: continue
                        any = true
                        if (key !in topArtists) fanKeys.add(key)
                        if (fanKeys.size >= MusicQuickPicks.SIMILAR_ARTIST_COUNT) break
                    }
                    if (!any) break
                    depth++
                }

                val similarPool =
                    fanKeys
                        .map { key -> async(PerformanceDispatcher.networkIO) { cachedArtistDetails(key) } }
                        .awaitAll()
                        .filterNotNull()
                        .flatMap { it.topTracks.audioMusicOnly().take(MusicQuickPicks.LANE_SIZE / 2) }
                        .distinctBy { it.videoId }
                if (similarPool.isNotEmpty()) lanes.add(similarPool)
            }

            // Round-robin one release per artist per pass, so no artist owns the shelf.
            val albums = ArrayList<MusicPlaylist>()
            var depth = 0
            while (albums.size < 12) {
                var any = false
                for (releases in releasesPerArtist) {
                    releases.getOrNull(depth)?.let {
                        albums.add(it)
                        any = true
                    }
                }
                if (!any) break
                depth++
            }
            return ArtistLanes(lanes, albums.distinctBy { it.id })
        }

        /**
         * Daily Mixes, desktop-style: cluster seeds from the brain's co-listening
         * graph, each expanded through related recall and ranked on the discovery
         * surface. Mixes are meant to be stable — no recently-shown avoidance.
         */
        private suspend fun refreshDailyMixes() {
            try {
                val sections = buildDailyMixSections()
                if (sections.isNotEmpty()) {
                    _uiState.update { it.copy(dailyMixSections = sections) }
                    dailyMixStore.publish(sections)
                }
            } catch (e: Exception) {
                Log.e("MusicViewModel", "Error building daily mixes", e)
            }
        }

        private suspend fun buildDailyMixSections(): List<MusicSection> {
            val mixes = musicBrain.dailyMixes(3)
            if (mixes.isEmpty()) return emptyList()
            // One parallel round for every mix's lanes — sequential rounds tripled
            // the wall-clock cost on a cold related-lane cache.
            val lanesByMix =
                kotlinx.coroutines.coroutineScope {
                    mixes
                        .map { mix ->
                            mix.seedTrackIds
                                .take(3)
                                .map { seedId ->
                                    async(PerformanceDispatcher.networkIO) { cachedRelatedLane(seedId) }
                                }
                        }.map { jobs -> jobs.awaitAll().flatten() }
                }
            val used = HashSet<String>()
            val sections = ArrayList<MusicSection>()
            for ((index, mix) in mixes.withIndex()) {
                val related = lanesByMix[index]
                val pool = musicBrain.rankTracks(related.distinctBy { it.videoId }, "discover")
                val items = pool.filterNot { it.videoId in used }.take(14)
                if (items.size < 4) continue
                used.addAll(items.map { it.videoId })
                sections.add(
                    MusicSection(
                        title = context.getString(R.string.section_daily_mix_title, mix.label),
                        label = context.getString(R.string.section_daily_mix_label),
                        thumbnailUrl = items.first().thumbnailUrl,
                        // The synthetic id routes the header tap to a playlist page.
                        seedId = "$DAILY_MIX_ID_PREFIX${sections.size}",
                        isArtistSeed = false,
                        tracks = items,
                    ),
                )
            }
            return sections
        }

        /**
         * The three brain-native shelves rendered purely from local meta:
         * On Repeat, the time-of-day rotation and Rediscover. Zero network,
         * refreshed together per track change (visibility-gated by the callers).
         */
        private suspend fun refreshLocalShelves() {
            try {
                val onRepeat = musicBrain.onRepeatShelf()
                val onRepeatIds = onRepeat.mapTo(HashSet()) { it.videoId }
                val rotation =
                    musicBrain
                        .timeOfDayTracks(20)
                        .audioMusicOnly()
                        .filterNot { it.videoId in onRepeatIds }
                val rediscover =
                    musicBrain
                        .rediscoverTracks(12)
                        .audioMusicOnly()
                        .filterNot { it.videoId in onRepeatIds }
                val history = playlistRepository.history.firstOrNull().orEmpty()
                val deepCuts =
                    musicGraph
                        .deepCuts(deepCutAlbumIds(history), history, DEEP_CUT_LIMIT)
                        .audioMusicOnly()
                        .filterNot { it.videoId in onRepeatIds }
                val playedArtistKeys = HashSet<String>()
                history.forEach { track ->
                    playedArtistKeys.add(track.primaryArtistKey())
                    playedArtistKeys.add(track.artist.trim().lowercase())
                }
                val seedArtistIds =
                    (
                        musicBrain.topArtistKeys(ARTISTS_FOR_YOU_SEEDS) +
                            history.mapNotNull { it.artists.firstOrNull()?.id ?: it.channelId.takeIf(String::isNotBlank) }
                    ).distinct()
                        .take(ARTISTS_FOR_YOU_SEEDS)
                val artistsForYou =
                    musicGraph.artistsForYou(seedArtistIds, playedArtistKeys + musicBrain.hiddenArtists.value, ARTISTS_FOR_YOU_LIMIT)
                _uiState.update {
                    it.copy(
                        onRepeatTracks = if (onRepeat.size >= 2) onRepeat else it.onRepeatTracks,
                        // Time-sensitive shelves hide rather than linger when thin.
                        rotationTracks = if (rotation.size >= 3) rotation else emptyList(),
                        rotationBucket = MusicTimeBucket.fromTimestamp(System.currentTimeMillis()),
                        rediscoverTracks = if (rediscover.size >= 3) rediscover else emptyList(),
                        deepCutTracks = if (deepCuts.size >= 3) deepCuts else emptyList(),
                        artistsForYou = if (artistsForYou.size >= 3) artistsForYou else emptyList(),
                    )
                }
            } catch (e: Exception) {
                Log.e("MusicViewModel", "Error loading local shelves", e)
            }
        }

        private suspend fun deepCutAlbumIds(history: List<MusicTrack>): List<String> {
            val topArtists = musicBrain.topArtistKeys(MusicQuickPicks.ARTIST_LANE_COUNT).toSet()
            return history
                .filter { !it.albumId.isNullOrBlank() }
                .sortedByDescending { it.primaryArtistKey() in topArtists }
                .mapNotNull { it.albumId }
                .distinct()
                .take(DEEP_CUT_ALBUM_POOL)
        }

        private suspend fun expandDeepCutAlbums(): Boolean {
            var recorded = false
            try {
                val history = playlistRepository.history.firstOrNull().orEmpty()
                musicGraph
                    .albumIdsNeedingTracks(deepCutAlbumIds(history))
                    .take(DEEP_CUT_ALBUM_FETCHES)
                    .forEach { albumId ->
                        InnertubeMusicService.fetchAlbum(albumId)?.let {
                            musicGraph.recordAlbum(it)
                            recorded = true
                        }
                    }
            } catch (e: Exception) {
                Log.e("MusicViewModel", "Error expanding deep cut albums", e)
            }
            return recorded
        }

        /**
         *  PERFORMANCE OPTIMIZED: Load all music content progressively
         *  Each section loads independently to show content as fast as possible
         */
        private fun loadMusicContent(force: Boolean = false) {
            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                val cachedTrending = MusicCache.getTrendingMusic(100)
                val cachedResult =
                    try {
                        musicRecommendationAlgorithm.loadMusicHome()
                    } catch (e: Exception) {
                        emptyList<MusicSection>() to null
                    }

                val cachedSections = cachedResult.first

                if (cachedTrending != null || cachedSections.isNotEmpty()) {
                    withContext(PerformanceDispatcher.parsing) {
                        // Apply cached data immediately
                        if (cachedSections.isNotEmpty()) {
                            processHomeSections(cachedSections)
                            cachedResult.second?.let { continuation ->
                                _uiState.update { it.copy(homeContinuation = continuation) }
                            }
                        }

                        cachedTrending?.let { trend ->
                            _uiState.update {
                                it.copy(
                                    trendingSongs = trend,
                                )
                            }
                        }

                        _uiState.update { it.copy(isLoading = false) }
                    }
                } else {
                    _uiState.update { it.copy(isLoading = true, error = null) }
                }
            }

            // On Repeat — served entirely from the local music brain, zero network.
            // Watch history holds one row per track, so backfill cannot seed relistens;
            // the shelf earns items only from live sessions and refreshes per track change.
            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                refreshLocalShelves()
                // Maturity steers which sections lead the page (planner-lite).
                runCatching { musicBrain.tasteProfile().maturity }
                    .getOrNull()
                    ?.let { maturity -> _uiState.update { it.copy(brainMaturity = maturity) } }
            }

            // 1. CRITICAL: Trending / Charts (Fastest & Most Important)
            val trendingJob =
                viewModelScope.launch(PerformanceDispatcher.networkIO) {
                    val trending =
                        withTimeoutOrNull(8_000L) {
                            try {
                                // Try to get charts first for high quality trending data
                                val charts = InnertubeMusicService.fetchCharts()
                                if (charts != null) {
                                    _uiState.update {
                                        it.copy(
                                            chartPlaylists = charts.playlists,
                                            chartArtists = charts.artists,
                                            chartCountryCode = charts.countryCode,
                                        )
                                    }
                                }
                                if (charts != null && charts.songs.isNotEmpty()) {
                                    MusicCache.cacheTrendingMusic(100, charts.songs)
                                    charts.songs
                                } else {
                                    val trending = YouTubeMusicService.fetchTrendingMusic(100)
                                    MusicCache.cacheTrendingMusic(100, trending)
                                    trending
                                }
                            } catch (e: Exception) {
                                Log.e("MusicViewModel", "Error loading trending/charts", e)
                                null
                            }
                        }

                    trending?.let { trend ->
                        _uiState.update {
                            it.copy(
                                trendingSongs = trend,
                                isLoading = false,
                            )
                        }
                    }

                    // First composition of the multi-lane Quick Picks: seeds from the
                    // current track (if any) and history, discovery lane from the charts.
                    rebuildQuickPicks(EnhancedMusicPlayerManager.currentTrack.value)
                }

            // 2. IMPORTANT: Home Sections (Dynamic Content)
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                var skippedFreshCache = false
                val homeResult =
                    withTimeoutOrNull(10_000L) {
                        // Reduced timeout
                        try {
                            if (force) {
                                musicRecommendationAlgorithm.refreshMusicHome()
                            } else {
                                // Cache inside its 4 h TTL: the cached pass already rendered it.
                                musicRecommendationAlgorithm.refreshMusicHomeIfStale()
                                    ?: (emptyList<MusicSection>() to null).also { skippedFreshCache = true }
                            }
                        } catch (e: Exception) {
                            Log.e("MusicViewModel", "Error refreshing home sections", e)
                            emptyList<MusicSection>() to null
                        }
                    } ?: (emptyList<MusicSection>() to null)

                val homeSections = homeResult.first
                val homeContinuation = homeResult.second

                // Fetch Chips — ordered by learned genre/mood affinity so the
                // moods the user actually plays lead the row (stable otherwise).
                val homeChips = musicRecommendationAlgorithm.getHomeChips()
                val genreAffinity = musicBrain.genreAffinitySnapshot()
                val orderedChips =
                    if (genreAffinity.isEmpty()) {
                        homeChips
                    } else {
                        homeChips.sortedByDescending { genreAffinity[it.title.trim().lowercase()] ?: 0.0 }
                    }
                _uiState.update { it.copy(homeChips = orderedChips) }

                if (homeSections.isNotEmpty()) {
                    processHomeSections(homeSections)
                    _uiState.update { it.copy(homeContinuation = homeContinuation) }
                } else if (!skippedFreshCache && !quickPicksComposed &&
                    _uiState.value.forYouTracks.isEmpty() && _uiState.value.dynamicSections.isEmpty()
                ) {
                    val recs = musicRecommendationAlgorithm.getRecommendations(24).audioMusicOnly()
                    if (recs.isNotEmpty()) {
                        val ranked = musicBrain.rankTracks(recs, "quick_picks")
                        _uiState.update { it.copy(forYouTracks = ranked) }
                    }
                }
                if (_uiState.value.trendingSongs.isNotEmpty() || homeSections.isNotEmpty()) {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }

            // 3. SECONDARY: History (Disk IO)
            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                val history =
                    withTimeoutOrNull(5_000L) {
                        try {
                            playlistRepository.history.firstOrNull() ?: emptyList()
                        } catch (e: Exception) {
                            emptyList()
                        }
                    } ?: emptyList()

                if (history.isNotEmpty()) {
                    _uiState.update {
                        it.copy(
                            history = history,
                            // Raw history is only an emergency placeholder — never over a composed shelf.
                            forYouTracks =
                                if (it.forYouTracks.isEmpty() && !quickPicksComposed) {
                                    history.audioMusicOnly().take(24)
                                } else {
                                    it.forYouTracks
                                },
                            isLoading = false,
                        )
                    }
                }
            }

            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                withTimeoutOrNull(SECONDARY_CONTENT_START_CAP_MS) { trendingJob.join() }
                loadSecondaryContent()
            }
        }

        private fun loadSecondaryContent() {
            // Daily Mixes — co-occurrence clusters expanded through related recall.
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                refreshDailyMixes()
            }

            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                if (expandDeepCutAlbums()) refreshLocalShelves()
            }

            // 4. CONTENT: New Releases (Albums & Tracks)
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                withTimeoutOrNull(10_000L) {
                    try {
                        // Fetch Album Releases (New Feature)
                        val albums = InnertubeMusicService.fetchNewReleases()
                        if (albums.isNotEmpty()) {
                            _uiState.update { it.copy(topAlbums = albums) }
                        }

                        val newReleases = YouTubeMusicService.fetchNewReleases(40)
                        if (newReleases.isNotEmpty()) {
                            _uiState.update { it.copy(newReleases = newReleases) }
                        }
                        Unit
                    } catch (e: Exception) {
                        Log.e("MusicViewModel", "Error loading new releases", e)
                    }
                }
            }

            // 5. CONTENT: Moods & Genres (New Section)
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                withTimeoutOrNull(8_000L) {
                    try {
                        val moods = InnertubeMusicService.fetchMoodAndGenres()
                        if (moods.isNotEmpty()) {
                            _uiState.update { it.copy(moodsAndGenres = moods) }
                        }
                        Unit
                    } catch (e: Exception) {
                        Log.e("MusicViewModel", "Error loading moods", e)
                    }
                }
            }

            // 6. CONTENT: Featured Playlists
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                withTimeoutOrNull(12_000L) {
                    try {
                        val history =
                            try {
                                playlistRepository.history.firstOrNull() ?: emptyList()
                            } catch (e: Exception) {
                                emptyList()
                            }

                        val query =
                            if (history.isNotEmpty()) {
                                val topArtists =
                                    history
                                        .groupBy { it.artist }
                                        .map { it.key to it.value.size }
                                        .sortedByDescending { it.second }
                                        .take(3)
                                        .map { it.first }
                                        .filter { !it.isNullOrBlank() }
                                        .shuffled()

                                val selectedArtist = topArtists.firstOrNull()
                                if (selectedArtist != null) {
                                    "$selectedArtist playlist"
                                } else {
                                    "curated music playlists 2026"
                                }
                            } else {
                                "curated music playlists 2026"
                            }

                        Log.d("MusicViewModel", "Personalized playlists query: $query")
                        val playlists = YouTubeMusicService.searchPlaylists(query, 10)
                        if (playlists.isNotEmpty()) {
                            _uiState.update { it.copy(featuredPlaylists = playlists) }
                        } else {
                            val fallback = YouTubeMusicService.searchPlaylists("curated music playlists 2026", 10)
                            if (fallback.isNotEmpty()) {
                                _uiState.update { it.copy(featuredPlaylists = fallback) }
                            }
                        }
                        Unit
                    } catch (e: Exception) {
                        Log.e("MusicViewModel", "Error loading playlists", e)
                    }
                }
            }

            // 7. BACKGROUND: Popular Artists & Genre Content
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                withTimeoutOrNull(12_000L) {
                    try {
                        val tracks = YouTubeMusicService.fetchPopularArtistMusic(50)
                        if (tracks.isNotEmpty()) {
                            MusicCache.cacheGenreTracks("Popular Artists", 50, tracks)
                            val currentGenreTracks = _uiState.value.genreTracks.toMutableMap()
                            currentGenreTracks["Popular Artists"] = tracks

                            val genres = YouTubeMusicService.getPopularGenres()
                            _uiState.update {
                                it.copy(
                                    genreTracks = currentGenreTracks,
                                    genres = listOf("Popular Artists") + genres,
                                )
                            }
                        }
                        Unit
                    } catch (e: Exception) {
                        Log.e("MusicViewModel", "Error loading popular artists", e)
                    }
                }

                // Load specific genres in background
                val genreList = listOf("Pop", "Rock", "Hip Hop", "R&B", "Electronic")
                val genreMap = mutableMapOf<String, List<MusicTrack>>()

                supervisorScope {
                    genreList
                        .map { genre ->
                            async(PerformanceDispatcher.networkIO) {
                                withTimeoutOrNull(8_000L) {
                                    try {
                                        val tracks = musicRecommendationAlgorithm.getGenreContent(genre)
                                        if (tracks.isNotEmpty()) {
                                            genre to tracks
                                        } else {
                                            null
                                        }
                                    } catch (e: Exception) {
                                        null
                                    }
                                }
                            }
                        }.forEach { deferred ->
                            deferred.await()?.let { (genre, tracks) ->
                                genreMap[genre] = tracks
                            }
                        }
                }

                if (genreMap.isNotEmpty()) {
                    _uiState.update {
                        val updated = it.genreTracks.toMutableMap()
                        updated.putAll(genreMap)
                        it.copy(genreTracks = updated)
                    }
                }
            }

            // 8. BACKGROUND: Explore Page
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                val explore = InnertubeMusicService.fetchExplore()
                if (explore != null) {
                    _uiState.update { it.copy(explorePage = explore) }
                }
            }

            // 9. DYNAMIC CONTENT: Similar To & Vibes
            loadDynamicContent()

            // 10. DAILY DISCOVER: seed-based carousel
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                loadDailyDiscover()
            }

            // 11. COMMUNITY: human-curated playlists based on listening history
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                loadCommunityPlaylists()
            }
        }

        private fun String.isCuratedPlaylistId(): Boolean = !startsWith("RD") && !startsWith("OLAK")

        private fun MusicPlaylist.isCommunityPlaylistCandidate(): Boolean {
            val normalizedAuthor = author.trim()
            return normalizedAuthor.isNotBlank() &&
                !normalizedAuthor.equals("YouTube", true) &&
                !normalizedAuthor.equals("YouTube Music", true) &&
                id.isCuratedPlaylistId()
        }

        private suspend fun loadCommunityPlaylists() {
            try {
                val history =
                    withContext(PerformanceDispatcher.diskIO) {
                        playlistRepository.history.firstOrNull() ?: emptyList()
                    }.audioMusicOnly()
                val trackSeeds = history.distinctBy { it.primaryArtistKey() }.take(COMMUNITY_TRACK_SEEDS)
                val artistKeys =
                    musicBrain.topArtistKeys(COMMUNITY_ARTIST_SEEDS).ifEmpty {
                        _uiState.value.trendingSongs
                            .map { it.channelId }
                            .filter { it.startsWith("UC") }
                            .distinct()
                            .take(COMMUNITY_ARTIST_SEEDS)
                    }
                if (trackSeeds.isEmpty() && artistKeys.isEmpty()) return

                val candidates =
                    supervisorScope {
                        val fromArtists =
                            artistKeys.map { key ->
                                async(PerformanceDispatcher.networkIO) {
                                    val details = cachedArtistDetails(key) ?: return@async emptyList()
                                    details.featuredOn.filterNot { it.author.equals(details.name, ignoreCase = true) }
                                }
                            }
                        val fromTracks =
                            trackSeeds.map { seed ->
                                async(PerformanceDispatcher.networkIO) {
                                    cachedRelated(seed.videoId, seed.primaryArtistKey(), seed)?.playlists.orEmpty()
                                }
                            }
                        (fromArtists + fromTracks).awaitAll().flatten()
                    }.filter { it.isCommunityPlaylistCandidate() }
                        .distinctBy { it.id }
                        .shuffled()
                        .take(COMMUNITY_PLAYLIST_COUNT)
                if (candidates.isEmpty()) return

                val cachedTracks = musicGraph.playlistTracksFor(candidates.map { it.id })
                Log.d("MusicViewModel", "Community playlists: graph=${cachedTracks.size} network=${candidates.size - cachedTracks.size}")
                val communityItems =
                    supervisorScope {
                        candidates
                            .map { playlist ->
                                async(PerformanceDispatcher.networkIO) {
                                    val allTracks =
                                        cachedTracks[playlist.id]
                                            ?: InnertubeMusicService
                                                .fetchPlaylistDetails(playlist.id)
                                                ?.also { musicGraph.recordPlaylist(it) }
                                                ?.tracks
                                            ?: return@async null
                                    val tracks = allTracks.audioMusicOnly().take(COMMUNITY_PREVIEW_TRACKS)
                                    if (tracks.isEmpty()) return@async null
                                    CommunityMusicPlaylist(
                                        playlist =
                                            playlist.copy(
                                                trackCount = allTracks.size,
                                                thumbnailUrl = playlist.thumbnailUrl.ifBlank { tracks.first().thumbnailUrl },
                                            ),
                                        tracks = tracks,
                                    )
                                }
                            }.awaitAll()
                            .filterNotNull()
                    }

                if (communityItems.isNotEmpty()) {
                    _uiState.update { it.copy(communityPlaylists = communityItems) }
                }
            } catch (e: Exception) {
                Log.e("MusicViewModel", "Error loading community playlists", e)
            }
        }

        private fun loadDynamicContent() {
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                val history = playlistRepository.history.firstOrNull() ?: emptyList()
                val blocks = mutableListOf<SimilarToBlock>()

                if (history.isNotEmpty()) {
                    try {
                        blocks +=
                            SimilarToSections
                                .topArtistSeeds(history, Random.Default)
                                .map { (artistName, artistTrack) ->
                                    async(PerformanceDispatcher.networkIO) {
                                        similarToBlock(artistTrack, title = artistName, seedId = artistTrack.channelId, isArtistSeed = true)
                                    }
                                }.awaitAll()
                                .filterNotNull()

                        val recentTrack = history.firstOrNull()
                        if (
                            recentTrack != null &&
                            recentTrack.videoId.isNotBlank() &&
                            blocks.none { it.similar.title == recentTrack.title || it.similar.title == recentTrack.artist }
                        ) {
                            similarToBlock(recentTrack, title = recentTrack.title, seedId = recentTrack.videoId, isArtistSeed = false)
                                ?.let { blocks += it }
                        }
                    } catch (e: Exception) {
                        Log.e("MusicViewModel", "Error loading similar to sections", e)
                    }
                }
                val similarSections = blocks.mapTo(mutableListOf()) { it.similar }

                // B. Random Vibe Playlists
                val vibes = listOf("Focus", "Relaxing", "Energize", "Commute", "Party", "Romance", "Sad", "Sleep", "Workout")
                val vibe = vibes.random()

                try {
                    val playlists = YouTubeMusicService.searchPlaylists("$vibe music playlists", 10)
                    if (playlists.isNotEmpty()) {
                        val playlistTracks =
                            playlists.map { playlist ->
                                MusicTrack(
                                    videoId = playlist.id,
                                    title = playlist.title,
                                    artist = playlist.author,
                                    thumbnailUrl = playlist.thumbnailUrl,
                                    duration = 0,
                                    itemType = MusicItemType.PLAYLIST,
                                )
                            }
                        similarSections.add(
                            MusicSection(
                                title = context.getString(R.string.section_vibe_vibes, vibe),
                                subtitle = context.getString(R.string.subtitle_community_playlists),
                                tracks = playlistTracks,
                            ),
                        )
                    }
                } catch (e: Exception) {
                    Log.e("MusicViewModel", "Error loading vibe playlists", e)
                }

                if (similarSections.isNotEmpty()) {
                    _uiState.update {
                        it.copy(
                            similarToSections = similarSections,
                            otherPerformanceSections = blocks.mapNotNull { block -> block.otherPerformances },
                            moreFromArtistSections =
                                blocks
                                    .mapNotNull { block ->
                                        block.moreFromArtist
                                    }.distinctBy { section -> section.seedId },
                        )
                    }
                }
            }
        }

        private suspend fun similarToBlock(
            seed: MusicTrack,
            title: String,
            seedId: String,
            isArtistSeed: Boolean,
        ): SimilarToBlock? {
            val related = cachedRelated(seed.videoId, seed.primaryArtistKey(), seed) ?: return null
            val songs = musicBrain.rankTracks(related.tracks.audioMusicOnly(), "similar")
            if (songs.isEmpty()) return null
            val random = Random(_uiState.value.sessionSeed xor seedId.hashCode().toLong())
            val artistId = related.seedArtistId ?: seed.artists.firstOrNull()?.id ?: seed.channelId.takeIf { it.startsWith("UC") }
            val artistName = seed.artists.firstOrNull { it.id == artistId }?.name ?: seed.artist
            val performances = related.otherPerformances.filter { it.videoId != seed.videoId }.distinctBy { it.videoId }
            return SimilarToBlock(
                similar =
                    MusicSection(
                        title = title,
                        label = context.getString(R.string.similar_to),
                        thumbnailUrl = seed.thumbnailUrl,
                        seedId = seedId,
                        isArtistSeed = isArtistSeed,
                        tracks = SimilarToSections.mixed(songs, related.similarArtists, related.playlists, random),
                    ),
                otherPerformances =
                    performances.takeIf { it.isNotEmpty() }?.let {
                        MusicSection(
                            title = related.seed?.title ?: seed.title,
                            label = context.getString(R.string.section_other_performances),
                            seedId = seedId,
                            tracks = it,
                        )
                    },
                moreFromArtist =
                    if (artistId != null && related.artistAlbums.isNotEmpty()) {
                        MusicSection(
                            title = artistName,
                            label = context.getString(R.string.section_more_from),
                            seedId = artistId,
                            isArtistSeed = true,
                            tracks = related.artistAlbums.map { it.asCollectionTrack(MusicItemType.ALBUM) },
                        )
                    } else {
                        null
                    },
            )
        }

        fun loadArtistItems(
            browseId: String,
            params: String?,
        ) {
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                _uiState.update { it.copy(isArtistItemsLoading = true, artistItemsPage = null) }
                YouTube
                    .artistItems(BrowseEndpoint(browseId, params))
                    .onSuccess { page ->
                        _uiState.update { it.copy(artistItemsPage = page, isArtistItemsLoading = false) }
                    }.onFailure {
                        _uiState.update { it.copy(isArtistItemsLoading = false) }
                    }
            }
        }

        fun loadMoreArtistItems() {
            val continuation = _uiState.value.artistItemsPage?.continuation ?: return
            if (_uiState.value.isMoreLoading) return

            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                _uiState.update { it.copy(isMoreLoading = true) }
                YouTube
                    .artistItemsContinuation(continuation)
                    .onSuccess { page ->
                        _uiState.update {
                            it.copy(
                                isMoreLoading = false,
                                artistItemsPage =
                                    it.artistItemsPage?.copy(
                                        items = it.artistItemsPage.items + page.items,
                                        continuation = page.continuation,
                                    ),
                            )
                        }
                    }.onFailure {
                        _uiState.update { it.copy(isMoreLoading = false) }
                    }
            }
        }

        // Helper to process sections to avoid code duplication
        private suspend fun processHomeSections(sections: List<MusicSection>) {
            val quickPicks =
                sections
                    .find {
                        it.title.contains("Quick picks", true) ||
                            it.title.contains("Start radio", true) ||
                            it.title.contains("Recommended", true) ||
                            it.title.contains("Mixed for you", true)
                    }?.tracks
                    ?.audioMusicOnly()
                    .orEmpty()

            val recommended =
                sections
                    .find {
                        it.title.contains("Mixed for you", true) ||
                            it.title.contains("Recommended", true) ||
                            it.title.contains("Listen again", true)
                    }?.tracks
                    ?.audioMusicOnly()
                    .orEmpty()

            val musicVideosForYou =
                sections
                    .find {
                        it.title.contains("Music videos for you", true)
                    }?.tracks ?: emptyList()

            val musicVideos =
                sections
                    .find {
                        it.title.contains("Music videos", true) || it.title.contains("Videos", true)
                    }?.tracks ?: musicVideosForYou

            val livePerformances =
                sections
                    .find {
                        it.title.contains("Live performances", true) ||
                            (it.title.contains("Live", true) && it.title.contains("performance", true))
                    }?.tracks ?: emptyList()

            val longListens =
                sections
                    .find {
                        it.title.contains("Long listens", true)
                    }?.tracks ?: emptyList()

            val listenAgain =
                sections
                    .find {
                        it.title.contains("Listen again", true)
                    }?.tracks
                    ?.audioMusicOnly() ?: emptyList()

            // YT hands these shelves back unranked; a brain pass puts the user's
            // taste first and drops blocked artists at the source.
            val rankedListenAgain = musicBrain.rankTracks(listenAgain, "heavy_rotation")
            val rankedRecommended = musicBrain.rankTracks(recommended, "quick_picks")
            val rankedVideosForYou = musicBrain.rankTracks(musicVideosForYou, "quick_picks")
            val rankedLongListens = musicBrain.rankTracks(longListens, "quick_picks")

            _uiState.update { currentState ->
                currentState.copy(
                    forYouTracks =
                        if (quickPicksComposed) {
                            currentState.forYouTracks
                        } else {
                            quickPicks.ifEmpty { currentState.forYouTracks }
                        },
                    recommendedTracks = rankedRecommended.ifEmpty { currentState.recommendedTracks },
                    listenAgain = rankedListenAgain,
                    musicVideos = musicVideos,
                    musicVideosForYou = rankedVideosForYou,
                    livePerformances = livePerformances,
                    longListens = rankedLongListens,
                    dynamicSections = sections,
                )
            }
        }

        fun setHomeChip(chip: HomePage.Chip?) = homeChipFeed.select(chip)

        fun retryHomeChip() = homeChipFeed.retry()

        fun loadMoreHomeChip() = homeChipFeed.loadMore()

        fun retry() {
            loadMusicContent()
        }

        fun refresh() {
            relatedCache.clear()
            artistDetailsCache.clear()
            if (homeChipFeed.refresh()) return
            _uiState.update { it.copy(isLoading = true) }
            loadMusicContent(force = true)
        }

        /**
         *  PERFORMANCE OPTIMIZED: Fetch artist details with timeout
         */
        fun fetchArtistDetails(channelId: String) {
            _uiState.update {
                it.copy(
                    isArtistLoading = true,
                    artistLoadFailed = false,
                    artistDetails = null,
                    artistInsights = null,
                    knownRelatedArtistIds = emptySet(),
                )
            }
            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                supervisorScope {
                    val detailsDeferred =
                        async(PerformanceDispatcher.networkIO) {
                            withTimeoutOrNull(10_000L) {
                                YouTubeMusicService.fetchArtistDetails(channelId)
                            }
                        }

                    val subscriptionDeferred =
                        async(PerformanceDispatcher.diskIO) {
                            subscriptionRepository.isSubscribed(channelId).firstOrNull() ?: false
                        }

                    val details = detailsDeferred.await()
                    val isSubscribed = subscriptionDeferred.await()

                    // The brain's history with this artist plus which of the
                    // "fans also like" row the user already listens to — local reads.
                    val insights = details?.let { musicBrain.artistInsights(channelId, it.name) }
                    val knownRelated =
                        details
                            ?.relatedArtists
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { related ->
                                val known = musicBrain.listenedArtistKeys()
                                related
                                    .filter { artist ->
                                        val key = musicArtistKey(artist.channelId.takeIf { it.isNotBlank() }, artist.name)
                                        key in known || artist.name.trim().lowercase() in known
                                    }.mapTo(HashSet()) { it.channelId }
                            }.orEmpty()

                    _uiState.value =
                        _uiState.value.copy(
                            isArtistLoading = false,
                            artistLoadFailed = details == null,
                            artistDetails = details?.copy(isSubscribed = isSubscribed),
                            artistInsights = insights,
                            knownRelatedArtistIds = knownRelated,
                        )
                }
            }
        }

        fun toggleFollowArtist(artist: ArtistDetails) {
            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                if (artist.isSubscribed) {
                    subscriptionRepository.unsubscribe(artist.channelId)
                } else {
                    subscriptionRepository.subscribe(
                        io.github.aedev.flow.data.local.ChannelSubscription(
                            channelId = artist.channelId,
                            channelName = artist.name,
                            channelThumbnail = artist.thumbnailUrl,
                            isMusic = true,
                        ),
                    )
                }

                // Update UI state
                val currentDetails = _uiState.value.artistDetails
                if (currentDetails?.channelId == artist.channelId) {
                    _uiState.value =
                        _uiState.value.copy(
                            artistDetails = currentDetails.copy(isSubscribed = !artist.isSubscribed),
                        )
                }
            }
        }

        fun clearArtistDetails() {
            _uiState.value =
                _uiState.value.copy(
                    artistDetails = null,
                    artistInsights = null,
                    knownRelatedArtistIds = emptySet(),
                )
        }

        fun loadMoreHomeContent() {
            val currentContinuation = _uiState.value.homeContinuation ?: return
            if (_uiState.value.isMoreLoading) return

            viewModelScope.launch(PerformanceDispatcher.networkIO) {
                _uiState.update { it.copy(isMoreLoading = true) }

                try {
                    val result = musicRecommendationAlgorithm.loadHomeContinuation(currentContinuation)
                    val newSections = result.first
                    val nextContinuation = result.second

                    if (newSections.isNotEmpty()) {
                        val currentSections = _uiState.value.dynamicSections.toMutableList()
                        currentSections.addAll(newSections)
                        _uiState.update {
                            it.copy(
                                dynamicSections = currentSections,
                                homeContinuation = nextContinuation,
                            )
                        }
                    } else {
                        _uiState.update { it.copy(homeContinuation = null) }
                    }
                } catch (e: Exception) {
                    Log.e("MusicViewModel", "Error loading more home content", e)
                } finally {
                    _uiState.update { it.copy(isMoreLoading = false) }
                }
            }
        }

        private suspend fun loadDailyDiscover() {
            try {
                val seeds =
                    withContext(PerformanceDispatcher.diskIO) {
                        val history = playlistRepository.history.firstOrNull() ?: emptyList()
                        history
                            .audioMusicOnly()
                            .shuffled()
                            .take(5)
                    }

                if (seeds.isEmpty()) return

                val items = java.util.Collections.synchronizedList(mutableListOf<DailyDiscoverItem>())

                kotlinx.coroutines.coroutineScope {
                    seeds
                        .map { seed ->
                            launch(PerformanceDispatcher.networkIO) {
                                try {
                                    val related =
                                        YouTubeMusicService
                                            .getRelatedMusic(seed.videoId, 16, audioOnly = true)
                                            .audioMusicOnly()
                                    val recommendation =
                                        musicBrain
                                            .rankTracks(related.filter { it.videoId != seed.videoId }, "discover")
                                            .firstOrNull { it.isAudioMusicCandidate() }
                                    if (recommendation != null) {
                                        items.add(DailyDiscoverItem(seed, recommendation))
                                    }
                                } catch (e: Exception) {
                                    Log.e("MusicViewModel", "Error fetching Daily Discover for seed ${seed.title}", e)
                                }
                            }
                        }.forEach { it.join() }
                }

                if (items.isNotEmpty()) {
                    val finalDiscover =
                        items
                            .toList()
                            .filter { it.seed.isAudioMusicCandidate() && it.recommendation.isAudioMusicCandidate() }
                            .distinctBy { it.recommendation.videoId }
                            .shuffled()
                    _uiState.update { it.copy(dailyDiscover = finalDiscover) }
                }
            } catch (e: Exception) {
                Log.e("MusicViewModel", "Error in loadDailyDiscover", e)
            }
        }
    }

data class MusicUiState(
    val sessionSeed: Long = System.currentTimeMillis(),
    val dailyDiscover: List<DailyDiscoverItem> = emptyList(),
    val onRepeatTracks: List<MusicTrack> = emptyList(), // On Repeat (local music brain)
    val rediscoverTracks: List<MusicTrack> = emptyList(), // Loved-but-quiet artists (local music brain)
    val deepCutTracks: List<MusicTrack> = emptyList(),
    val artistsForYou: List<ArtistDetails> = emptyList(),
    val rotationTracks: List<MusicTrack> = emptyList(), // Time-of-day rotation (local music brain)
    val rotationBucket: MusicTimeBucket? = null,
    val speedDialTracks: List<MusicTrack> = emptyList(), // Brain-ranked speed dial pool
    val forYouTracks: List<MusicTrack> = emptyList(), // Quick Picks
    val recommendedTracks: List<MusicTrack> = emptyList(), // Recommended for you
    val listenAgain: List<MusicTrack> = emptyList(), // Listen Again
    val trendingSongs: List<MusicTrack> = emptyList(),
    val chartPlaylists: List<MusicPlaylist> = emptyList(),
    val chartArtists: List<ArtistDetails> = emptyList(),
    val chartCountryCode: String? = null,
    val newReleases: List<MusicTrack> = emptyList(),
    val musicVideos: List<MusicTrack> = emptyList(),
    val musicVideosForYou: List<MusicTrack> = emptyList(),
    val livePerformances: List<MusicTrack> = emptyList(),
    val communityPlaylists: List<CommunityMusicPlaylist> = emptyList(),
    val longListens: List<MusicTrack> = emptyList(),
    val history: List<MusicTrack> = emptyList(),
    val genreTracks: Map<String, List<MusicTrack>> = emptyMap(),
    val genres: List<String> = emptyList(),
    val featuredPlaylists: List<MusicPlaylist> = emptyList(),
    val topAlbums: List<MusicPlaylist> = emptyList(),
    val favoriteArtistAlbums: List<MusicPlaylist> = emptyList(), // Releases from the brain's top artists
    val dynamicSections: List<MusicSection> = emptyList(),
    val dailyMixSections: List<MusicSection> = emptyList(),
    val homeChips: List<HomePage.Chip> = emptyList(),
    val homeChipPage: MusicHomeChipPage? = null,
    val brainMaturity: String? = null, // "cold_start" / "warming" / "mature" — steers section order
    val explorePage: io.github.aedev.flow.innertube.pages.ExplorePage? = null,
    val moodsAndGenres: List<MoodAndGenres> = emptyList(),
    val selectedGenre: String? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val downloadedTrackIds: Set<String> = emptySet(),
    val artistDetails: ArtistDetails? = null,
    val artistInsights: MusicArtistInsights? = null,
    val knownRelatedArtistIds: Set<String> = emptySet(),
    val isArtistLoading: Boolean = false,
    val artistLoadFailed: Boolean = false,
    val isMoreLoading: Boolean = false,
    val searchResultsArtists: List<ArtistDetails> = emptyList(),
    val homeContinuation: String? = null,
    val artistItemsPage: io.github.aedev.flow.innertube.pages.ArtistItemsPage? = null,
    val isArtistItemsLoading: Boolean = false,
    val similarToSections: List<MusicSection> = emptyList(),
    val otherPerformanceSections: List<MusicSection> = emptyList(),
    val moreFromArtistSections: List<MusicSection> = emptyList(),
)
