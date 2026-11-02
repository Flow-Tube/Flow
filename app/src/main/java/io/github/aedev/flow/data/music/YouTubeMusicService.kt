package io.github.aedev.flow.data.music

import android.util.Log
import io.github.aedev.flow.data.music.model.ArtistDetails
import io.github.aedev.flow.data.music.model.MusicArtist
import io.github.aedev.flow.data.music.model.MusicPlaylist
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.music.model.PlaylistDetails
import io.github.aedev.flow.data.newmusic.InnertubeMusicService
import io.github.aedev.flow.innertube.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** YouTube Music catalog access for trending, search, genres, playlists and artists, over InnerTube. */
object YouTubeMusicService {
    private const val TAG = "YouTubeMusicService"

    // Popular music categories and search queries
    private val musicGenres =
        listOf(
            "Pop Music",
            "Hip Hop Music",
            "Rock Music",
            "Electronic Music",
            "R&B Music",
            "Jazz Music",
            "Classical Music",
            "Country Music",
            "Indie Music",
            "Latin Music",
            "K-Pop Music",
            "Dance Music",
            "Reggae Music",
            "Blues Music",
            "Metal Music",
        )

    // Popular artist queries for quality content
    private val popularArtistQueries =
        listOf(
            "The Weeknd",
            "Taylor Swift",
            "Drake",
            "Ariana Grande",
            "Ed Sheeran",
            "Billie Eilish",
            "Post Malone",
            "Dua Lipa",
            "Bad Bunny",
            "SZA",
            "Kendrick Lamar",
            "Bruno Mars",
        )

    private val trendingMusicQueries =
        listOf(
            "official music video 2025",
            "new songs 2025 official",
            "top music hits 2025",
            "trending songs 2025",
        )

    /**
     * Fetch trending music tracks from YouTube
     * Uses Innertube (Hybrid approach) for better metadata
     */
    suspend fun fetchTrendingMusic(limit: Int = 50): List<MusicTrack> =
        withContext(Dispatchers.IO) {
            val tracks = mutableListOf<MusicTrack>()

            try {
                // Priority: Innertube Home Data (Official YT Music Home)
                val innertubeTracks = InnertubeMusicService.fetchTrendingMusic()
                if (innertubeTracks.isNotEmpty()) {
                    Log.d(TAG, "Fetched ${innertubeTracks.size} tracks from Innertube")
                    tracks.addAll(innertubeTracks)
                } else {
                    // Fallback: Innertube charts (FEmusic_charts — stable, no hardcoded IDs)
                    InnertubeMusicService.fetchCharts()?.songs?.let(tracks::addAll)
                    // Secondary fallback: search-based discovery
                    if (tracks.size < limit) {
                        tracks.addAll(searchMusic(trendingMusicQueries.first(), limit - tracks.size))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in fetchTrendingMusic", e)
                try {
                    InnertubeMusicService.fetchCharts()?.songs?.let(tracks::addAll)
                } catch (ignore: Exception) {
                }
            }

            val result = tracks.distinctBy { it.videoId }.take(limit)
            Log.d(TAG, "Fetched ${result.size} trending music tracks")
            result
        }

    /**
     * Fetch new releases using curated playlists
     */
    suspend fun fetchNewReleases(limit: Int = 30): List<MusicTrack> = searchMusic("new music releases 2025", limit)

    /**
     * Search for music tracks on YouTube
     */
    suspend fun searchMusic(
        query: String,
        limit: Int = 50,
    ): List<MusicTrack> =
        withContext(Dispatchers.IO) {
            try {
                InnertubeMusicService.searchMusic(query).take(limit)
            } catch (e: Exception) {
                Log.e(TAG, "Error searching music", e)
                emptyList()
            }
        }

    /**
     * Fetch full playlist details (info + tracks)
     */
    suspend fun fetchPlaylistDetails(playlistId: String): PlaylistDetails? =
        withContext(Dispatchers.IO) {
            try {
                if (playlistId.startsWith("MPREb_") || playlistId.startsWith("FEmusic_library_privately_owned_release_")) {
                    return@withContext InnertubeMusicService.fetchAlbum(playlistId)
                }
                InnertubeMusicService.fetchPlaylistDetails(playlistId)?.let { return@withContext it }
                if (!playlistId.startsWith("FMDM") && !playlistId.startsWith("OLAK")) return@withContext null

                val albumPage = YouTube.album(playlistId).getOrNull() ?: return@withContext null
                val tracks = albumPage.songs.map { convertSongItemToMusicTrack(it) }
                PlaylistDetails(
                    id = playlistId,
                    title = albumPage.album.title,
                    thumbnailUrl = albumPage.album.thumbnail,
                    author =
                        albumPage.album.artists
                            ?.firstOrNull()
                            ?.name ?: "Unknown Artist",
                    authorId =
                        albumPage.album.artists
                            ?.firstOrNull()
                            ?.id,
                    authorAvatarUrl = null,
                    trackCount = tracks.size,
                    description = null,
                    views = null,
                    durationText = formatTotalDuration(tracks.sumOf { it.duration }),
                    dateText = albumPage.album.year?.toString(),
                    tracks = tracks,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching playlist details: $playlistId", e)
                null
            }
        }

    /**
     * Fetch more tracks for a playlist using a continuation token
     */
    suspend fun fetchPlaylistContinuation(
        playlistId: String,
        continuation: String,
    ): Pair<List<MusicTrack>, String?> =
        withContext(Dispatchers.IO) {
            try {
                InnertubeMusicService.fetchPlaylistContinuation(playlistId, continuation)
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching continuation for $playlistId", e)
                emptyList<MusicTrack>() to null
            }
        }

    private fun formatTotalDuration(totalSeconds: Int): String {
        if (totalSeconds <= 0) return ""
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) "$hours hr $minutes min" else "$minutes min $seconds sec"
    }

    private fun convertSongItemToMusicTrack(item: io.github.aedev.flow.innertube.models.SongItem): MusicTrack =
        MusicTrack(
            videoId = item.id,
            title = item.title,
            artist = item.artists.firstOrNull()?.name ?: "Unknown Artist",
            thumbnailUrl = item.thumbnail,
            duration = item.duration ?: 0,
            views = 0,
            album = item.album?.name ?: "",
            channelId = item.artists.firstOrNull()?.id ?: "",
            isExplicit = item.explicit,
            isVideoSong = item.isVideoSong,
            artists = item.artists.map { MusicArtist(it.name, it.id) },
        )

    /**
     * Get related/similar music tracks
     */
    suspend fun getRelatedMusic(
        videoId: String,
        limit: Int = 20,
        audioOnly: Boolean = false,
    ): List<MusicTrack> =
        withContext(Dispatchers.IO) {
            try {
                InnertubeMusicService
                    .getRelatedMusic(videoId, audioOnly)
                    .filterNot { audioOnly && it.isVideoSong }
                    .take(limit)
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching related music", e)
                emptyList()
            }
        }

    /**
     * Fetch music from popular artists
     */
    suspend fun fetchPopularArtistMusic(limit: Int = 30): List<MusicTrack> =
        withContext(Dispatchers.IO) {
            try {
                val artistQueries = popularArtistQueries.shuffled().take(4)
                val tracks = mutableListOf<MusicTrack>()

                artistQueries.forEach { artist ->
                    tracks.addAll(searchMusic("$artist hits", 10))
                }

                tracks.distinctBy { it.videoId }.take(limit)
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching popular artist music", e)
                emptyList()
            }
        }

    /**
     * Search for playlists (albums)
     */
    suspend fun searchPlaylists(
        query: String,
        limit: Int = 10,
    ): List<MusicPlaylist> =
        withContext(Dispatchers.IO) {
            try {
                InnertubeMusicService.searchPlaylists(query).take(limit)
            } catch (e: Exception) {
                Log.e(TAG, "Error searching playlists", e)
                emptyList()
            }
        }

    /** The artist page, or null when it could not be loaded; the page shows an error for that. */
    suspend fun fetchArtistDetails(channelId: String): ArtistDetails? =
        withContext(Dispatchers.IO) {
            try {
                InnertubeMusicService.fetchArtistDetails(channelId)
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching artist details for $channelId", e)
                null
            }
        }

    fun getPopularGenres(): List<String> = musicGenres
}
