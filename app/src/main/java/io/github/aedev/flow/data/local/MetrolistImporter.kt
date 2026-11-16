package io.github.aedev.flow.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.net.Uri
import androidx.room.withTransaction
import io.github.aedev.flow.data.local.entity.PlaylistEntity
import io.github.aedev.flow.data.local.entity.PlaylistVideoCrossRef
import io.github.aedev.flow.data.local.entity.VideoEntity
import io.github.aedev.flow.utils.ThumbnailUrlResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Imports music playlists from a Metrolist backup file (ZIP containing "song.db").
 *
 * The backup is a ZIP archive with two entries:
 *   • settings.preferences_pb   – app settings (ignored)
 *   • song.db                   – Room/SQLite database
 *
 * Tables read:
 *   playlist          (id TEXT, name TEXT, thumbnailUrl TEXT, isLocal INTEGER)
 *   song              (id TEXT, title TEXT, thumbnailUrl TEXT, duration INTEGER, isLocal INTEGER)
 *   playlist_song_map (playlistId TEXT, songId TEXT, position INTEGER)
 *   song_artist_map   (songId TEXT, artistId TEXT, position INTEGER), joined to artist (id, name, channelId)
 *
 * All imported content is tagged isMusic = true so it appears in the Music section.
 */
internal class MetrolistImporter(
    private val context: Context,
    private val database: AppDatabase,
) {
    suspend fun import(
        uri: Uri,
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
    ): Result<Int> =
        withContext(Dispatchers.IO) {
            val tempDb = java.io.File(context.cacheDir, "metrolist_import_${System.currentTimeMillis()}.db")
            try {
                // 1. Extract "song.db" from the ZIP archive
                var foundDb = false
                context.contentResolver.openInputStream(uri)?.use { raw ->
                    java.util.zip.ZipInputStream(raw.buffered()).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            if (entry.name == "song.db") {
                                tempDb.outputStream().use { zip.copyTo(it) }
                                foundDb = true
                                break
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                }

                if (!foundDb) return@withContext Result.failure(Exception("invalid_format"))

                // 2. Open the extracted database read-only
                val db =
                    SQLiteDatabase.openDatabase(
                        tempDb.absolutePath,
                        null,
                        SQLiteDatabase.OPEN_READONLY,
                    )

                var importedCount = 0

                db.use {
                    // 3. Load playlists (exclude local-file playlists)
                    val playlists = mutableListOf<Triple<String, String, String>>()
                    db.rawQuery("SELECT id, name, COALESCE(thumbnailUrl,'') FROM playlist WHERE isLocal = 0", null).use { c ->
                        while (c.moveToNext()) playlists.add(Triple(c.getString(0), c.getString(1), c.getString(2)))
                    }

                    // 4. Load non-local songs
                    data class SongRow(
                        val id: String,
                        val title: String,
                        val thumb: String,
                        val duration: Int,
                    )
                    val songs = mutableMapOf<String, SongRow>()
                    db
                        .rawQuery(
                            "SELECT id, title, COALESCE(thumbnailUrl,''), COALESCE(duration,0) FROM song WHERE isLocal = 0",
                            null,
                        ).use { c ->
                            while (c.moveToNext()) {
                                songs[c.getString(0)] = SongRow(c.getString(0), c.getString(1), c.getString(2), c.getInt(3))
                            }
                        }

                    val credits = metrolistCredits(readArtistRows(db))

                    // 5. Load playlist-song mappings ordered by position
                    val playlistSongs = mutableMapOf<String, MutableList<Pair<String, Int>>>()
                    db.rawQuery("SELECT playlistId, songId, position FROM playlist_song_map ORDER BY playlistId, position", null).use { c ->
                        while (c.moveToNext()) {
                            playlistSongs
                                .getOrPut(c.getString(0)) { mutableListOf() }
                                .add(Pair(c.getString(1), c.getInt(2)))
                        }
                    }

                    val total = playlists.size
                    var done = 0
                    onProgress?.invoke(0, total)

                    // 6. Insert into Flow's database
                    database.withTransaction {
                        for ((plId, plName, plThumb) in playlists) {
                            val songList = playlistSongs[plId] ?: emptyList()
                            val newPlId = "metro_${System.currentTimeMillis()}_${plId.take(8)}"
                            val thumbUrl =
                                plThumb.takeIf { it.isNotEmpty() }
                                    ?: songList.firstOrNull()?.let { (sid, _) ->
                                        songs[sid]?.thumb?.takeIf { it.isNotEmpty() }
                                    }
                                    ?: ""

                            database.playlistDao().insertPlaylist(
                                PlaylistEntity(
                                    id = newPlId,
                                    name = plName,
                                    description = "Imported from Metrolist",
                                    thumbnailUrl = thumbUrl,
                                    isPrivate = false,
                                    createdAt = System.currentTimeMillis(),
                                    isMusic = true,
                                ),
                            )

                            songList.forEachIndexed { index, (songId, _) ->
                                val row = songs[songId]
                                val thumb =
                                    row?.thumb?.ifEmpty {
                                        ThumbnailUrlResolver.buildHighQualityYoutubeThumbnail(songId)
                                    } ?: ThumbnailUrlResolver.buildHighQualityYoutubeThumbnail(songId)

                                val credit = credits[songId]
                                // Fills what an earlier import left blank, so importing again repairs it.
                                database.videoDao().mergeMetadata(
                                    listOf(
                                        VideoEntity(
                                            id = songId,
                                            title = row?.title ?: "",
                                            channelName = credit?.names.orEmpty(),
                                            channelId = credit?.channelId.orEmpty(),
                                            thumbnailUrl = thumb,
                                            duration = row?.duration ?: 0,
                                            viewCount = 0L,
                                            uploadDate = "",
                                            description = "",
                                            channelThumbnailUrl = "",
                                            isMusic = true,
                                        ),
                                    ),
                                )
                                database.playlistDao().insertPlaylistVideoCrossRef(
                                    PlaylistVideoCrossRef(
                                        playlistId = newPlId,
                                        videoId = songId,
                                        position = index.toLong(),
                                    ),
                                )
                                importedCount++
                            }

                            done++
                            onProgress?.invoke(done, total)
                        }
                    }
                }

                if (importedCount == 0) return@withContext Result.failure(Exception("no_content"))
                Result.success(importedCount)
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                tempDb.delete()
            }
        }

    // Older backups predate the artist tables; their songs import without artists, as before.
    private fun readArtistRows(db: SQLiteDatabase): List<MetrolistArtistRow> =
        try {
            db
                .rawQuery(
                    "SELECT m.songId, a.name, a.channelId, a.id, m.position FROM song_artist_map m JOIN artist a ON a.id = m.artistId",
                    null,
                ).use { c ->
                    buildList {
                        while (c.moveToNext()) {
                            add(
                                MetrolistArtistRow(
                                    songId = c.getString(0),
                                    name = c.getString(1),
                                    channelId = c.getString(2)?.takeIf { it.isNotBlank() } ?: c.getString(3),
                                    position = c.getInt(4),
                                ),
                            )
                        }
                    }
                }
        } catch (e: SQLiteException) {
            emptyList()
        }
}

internal data class MetrolistArtistRow(
    val songId: String,
    val name: String?,
    val channelId: String?,
    val position: Int,
)

internal data class MetrolistCredit(
    val names: String,
    val channelId: String,
)

/** Every song's artists in credit order, joined the way the app shows them, with the first artist's channel. */
internal fun metrolistCredits(rows: List<MetrolistArtistRow>): Map<String, MetrolistCredit> =
    rows
        .groupBy { it.songId }
        .mapNotNull { (songId, artists) ->
            val named = artists.sortedBy { it.position }.filter { !it.name.isNullOrBlank() }
            val first = named.firstOrNull() ?: return@mapNotNull null
            songId to
                MetrolistCredit(
                    names = named.joinToString(", ") { it.name.orEmpty().trim() },
                    channelId = first.channelId?.takeIf { it.startsWith("UC") }.orEmpty(),
                )
        }.toMap()
