package io.github.aedev.flow.data.music.artwork

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.models.SongItem
import io.github.aedev.flow.network.ProxyAwareClient
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Album motion artwork for the music player, from the artwork service that sits beside the
 * BetterLyrics lyrics API. It returns Apple Music's loops for an album, sends nothing but the
 * song's title, artist, album and length, and needs no account. Each album is asked about once;
 * the answer, loop or none, is kept for days, as the service asks of its clients. A row with no
 * album, usually a music video, is asked about again under its song version's album on a miss.
 * Apple often has several releases of a song and the service can land on one with no loop, so a
 * remaining miss looks the song up in Apple's public search and asks about its other releases.
 */
@Singleton
class AnimatedArtworkRepository internal constructor(
    private val fetch: suspend (Map<String, String>) -> ArtworkAnswer,
    private val searchSongs: suspend (String) -> List<SongItem>,
    private val searchCatalog: suspend (String, String) -> List<CatalogSong>,
    private val cache: AnimatedArtworkCache,
    private val storefront: suspend () -> String,
    private val now: () -> Long,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        preferences: PlayerPreferences,
    ) : this(
        fetch = ArtworkService::fetch,
        // Not YouTubeMusicService.searchMusic: it turns a failed search into an empty list, which would be kept as a miss for days.
        searchSongs = { query ->
            withContext(PerformanceDispatcher.networkIO) {
                YouTube
                    .search(query, YouTube.SearchFilter.FILTER_SONG)
                    .getOrThrow()
                    .items
                    .filterIsInstance<SongItem>()
            }
        },
        searchCatalog = ArtworkService::searchCatalog,
        cache = DataStoreAnimatedArtworkCache(context),
        storefront = { preferences.trendingRegion.first() },
        now = System::currentTimeMillis,
    )

    private val lock = Mutex()

    /** The HLS loops for [row]'s album, or null when it has none. Throws when the service could not answer. */
    internal suspend fun loopFor(row: MusicTrack): AnimatedLoop? =
        lock.withLock {
            val track = AnimatedArtworkLookup.withKnownAlbum(row)
            val key = AnimatedArtworkLookup.key(track)
            cache.get(key)?.takeIf { now() - it.atMs < ttlFor(it) }?.let { return@withLock it.loop }
            val asked = mutableSetOf<String>()
            val loop = lookUp(track, asked)
            cache.put(key, CachedLoop(loop, now()))
            loop
        }

    /** Drops [row]'s answer, for a loop that no longer plays. */
    suspend fun forget(row: MusicTrack) = cache.remove(AnimatedArtworkLookup.key(AnimatedArtworkLookup.withKnownAlbum(row)))

    private suspend fun ask(
        track: MusicTrack,
        asked: MutableSet<String>,
    ): AnimatedLoop? {
        val country = storefront().lowercase()
        for (query in AnimatedArtworkLookup.queries(track)) {
            val params =
                buildMap {
                    put("s", query.title)
                    put("a", query.artist)
                    track.album.takeIf(String::isNotBlank)?.let { put("al", it) }
                    track.duration.takeIf { it > 0 }?.let { put("d", it.toString()) }
                    put("storefront", country)
                }
            val answer = fetch(params)
            if (AnimatedArtworkLookup.accepts(track, answer)) {
                answer.albumId?.let(asked::add)
                return answer.loop()
            }
        }
        return null
    }

    private suspend fun lookUp(
        track: MusicTrack,
        asked: MutableSet<String>,
    ): AnimatedLoop? {
        ask(track, asked)?.let { return it }
        val version = albumVersion(track)
        version?.let { ask(it, asked) }?.let { return it }
        return askOtherReleases(version ?: track, asked)
    }

    private suspend fun albumVersion(track: MusicTrack): MusicTrack? {
        if (track.album.isNotBlank()) return null
        return AnimatedArtworkLookup.albumVersion(track, searchSongs(AnimatedArtworkLookup.songSearch(track)))
    }

    private suspend fun askOtherReleases(
        track: MusicTrack,
        asked: Set<String>,
    ): AnimatedLoop? {
        val country = storefront().lowercase()
        val songs = searchCatalog(AnimatedArtworkLookup.songSearch(track), country)
        for (albumId in AnimatedArtworkLookup.otherReleases(track, songs, asked)) {
            fetch(mapOf("id" to albumId, "storefront" to country)).loop()?.let { return it }
        }
        return null
    }

    private fun ttlFor(answer: CachedLoop): Long = if (answer.loop != null) FOUND_TTL_MS else MISSING_TTL_MS

    private companion object {
        const val FOUND_TTL_MS = 14L * 24 * 60 * 60 * 1000
        const val MISSING_TTL_MS = 3L * 24 * 60 * 60 * 1000
    }
}

private object ArtworkService {
    private const val ENDPOINT = "https://artwork.boidu.dev/"
    private const val CATALOG_SEARCH = "https://itunes.apple.com/search"
    private const val CATALOG_RESULTS = "25"
    private val http = ProxyAwareClient()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(params: Map<String, String>): ArtworkAnswer = json.decodeFromString(ArtworkAnswer.serializer(), get(ENDPOINT, params))

    suspend fun searchCatalog(
        term: String,
        country: String,
    ): List<CatalogSong> {
        val params = mapOf("term" to term, "entity" to "song", "country" to country, "limit" to CATALOG_RESULTS)
        return json.decodeFromString(CatalogSearch.serializer(), get(CATALOG_SEARCH, params)).results
    }

    private suspend fun get(
        endpoint: String,
        params: Map<String, String>,
    ): String =
        withContext(PerformanceDispatcher.networkIO) {
            val url =
                endpoint
                    .toHttpUrl()
                    .newBuilder()
                    .apply { params.forEach { (name, value) -> addQueryParameter(name, value) } }
                    .build()
            http.get().newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("$endpoint answered ${response.code}")
                response.body.string()
            }
        }
}
