package io.github.aedev.flow.data.backup

import android.net.Uri
import androidx.annotation.StringRes
import io.github.aedev.flow.R
import java.time.Instant

private const val JSON = "application/json"
private const val ZIP = "application/zip"
private const val BINARY = "application/octet-stream"
private const val ANY = "*/*"
private val CsvTypes = arrayOf("text/comma-separated-values", "text/csv", "text/plain")
private val ZipTypes = arrayOf(ZIP, BINARY, ANY)

/** Something Flow can export, with the file type and name the system save dialog is offered. */
enum class ExportKind(
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    val mimeType: String,
    private val fileStem: String,
    private val extension: String,
    private val timestamped: Boolean = true,
) {
    MASTER(R.string.master_backup_title, R.string.master_backup_subtitle, ZIP, "flow_master_backup", "zip"),
    APP_DATA(R.string.export_app_data_title, R.string.export_app_data_desc, JSON, "flow_backup", "json"),
    ENGINE(R.string.export_engine_data, R.string.export_engine_data_subtitle, JSON, "flow_engine", "json"),
    MUSIC_BRAIN(R.string.export_music_brain_title, R.string.export_music_brain_desc, JSON, "flow_music_brain", "json"),
    NEWPIPE_SUBSCRIPTIONS(R.string.export_newpipe_subs_title, R.string.export_newpipe_subs_desc, JSON, "newpipe_subscriptions", "json"),
    WATCH_HISTORY(
        R.string.export_watch_history_title,
        R.string.export_watch_history_desc,
        JSON,
        "flow-watch-history",
        "json",
        timestamped = false,
    ),
    PLAYLISTS(R.string.export_playlists_title, R.string.export_playlists_desc, ZIP, "flow_playlists", "zip"),
    ;

    fun fileName(now: Instant = Instant.now()): String =
        if (timestamped) "${fileStem}_${now.toEpochMilli()}.$extension" else "$fileStem.$extension"

    fun start(
        coordinator: BackupCoordinator,
        uri: Uri,
    ) = when (this) {
        MASTER -> coordinator.exportMaster(uri)
        APP_DATA -> coordinator.exportAppData(uri)
        ENGINE -> coordinator.exportEngine(uri)
        MUSIC_BRAIN -> coordinator.exportMusicBrain(uri)
        NEWPIPE_SUBSCRIPTIONS -> coordinator.exportNewPipeSubscriptions(uri)
        WATCH_HISTORY -> coordinator.exportWatchHistory(uri)
        PLAYLISTS -> coordinator.exportPlaylists(uri)
    }
}

/**
 * Something Flow can import, and the file types the system picker should offer for it. With
 * [takesSeveralFiles], the picker lets the viewer choose several files that make up one import.
 */
enum class ImportKind(
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    val mimeTypes: Array<String>,
    val takesSeveralFiles: Boolean = false,
) {
    FLOW_BACKUP(R.string.import_flow_backup_item_title, R.string.import_flow_backup_desc, arrayOf(JSON)),
    MASTER(R.string.import_master_backup_title, R.string.import_master_backup_desc, arrayOf(ZIP, BINARY)),
    ENGINE(R.string.import_engine_data, R.string.import_engine_data_desc, arrayOf(JSON)),
    MUSIC_BRAIN(R.string.import_music_brain_title, R.string.import_music_brain_desc, arrayOf(JSON)),
    TAKEOUT(R.string.import_yt_takeout_all, R.string.import_yt_takeout_all_desc, ZipTypes, takesSeveralFiles = true),
    YOUTUBE_SUBSCRIPTIONS(R.string.import_from_youtube, R.string.import_from_youtube_desc, CsvTypes),
    YOUTUBE_HISTORY(R.string.import_yt_watch_history, R.string.import_yt_watch_history_desc, arrayOf("text/html", "text/plain", ANY)),
    YOUTUBE_LIKES(R.string.import_yt_likes, R.string.import_yt_likes_desc, arrayOf(JSON, ZIP, BINARY, ANY)),
    YOUTUBE_PLAYLIST(R.string.import_yt_playlist, R.string.import_yt_playlist_desc, CsvTypes),
    WATCH_LATER(R.string.import_yt_watch_later, R.string.import_yt_watch_later_desc, CsvTypes),
    YOUTUBE_MUSIC_PLAYLIST(R.string.import_yt_music_playlist, R.string.import_yt_music_playlist_desc, CsvTypes),
    NEWPIPE_SUBSCRIPTIONS(R.string.import_from_newpipe, R.string.import_from_newpipe_desc, arrayOf(JSON)),
    NEWPIPE_HISTORY(
        R.string.import_newpipe_history,
        R.string.import_newpipe_history_desc,
        arrayOf(ZIP, BINARY, "application/x-sqlite3", ANY),
    ),
    NEWPIPE_PLAYLISTS(R.string.import_newpipe_playlists, R.string.import_newpipe_playlists_desc, ZipTypes),
    LIBRETUBE_SUBSCRIPTIONS(R.string.import_from_libretube, R.string.import_from_libretube_desc, arrayOf(JSON)),
    LIBRETUBE_PLAYLISTS(R.string.import_libretube_playlists, R.string.import_libretube_playlists_desc, arrayOf(JSON)),
    FREETUBE_HISTORY(R.string.import_freetube_history, R.string.import_freetube_history_desc, arrayOf(JSON, "text/plain", BINARY, ANY)),
    METROLIST(R.string.import_from_metrolist, R.string.import_from_metrolist_desc, ZipTypes),
    ;

    fun start(
        coordinator: BackupCoordinator,
        uris: List<Uri>,
    ): Boolean {
        if (uris.isEmpty()) return false
        if (this == TAKEOUT) return coordinator.importYouTubeTakeout(uris)
        return start(coordinator, uris.first())
    }

    private fun start(
        coordinator: BackupCoordinator,
        uri: Uri,
    ) = when (this) {
        FLOW_BACKUP -> coordinator.importFlowBackup(uri)
        MASTER -> coordinator.importMaster(uri)
        ENGINE -> coordinator.importEngine(uri)
        MUSIC_BRAIN -> coordinator.importMusicBrain(uri)
        TAKEOUT -> coordinator.importYouTubeTakeout(listOf(uri))
        YOUTUBE_SUBSCRIPTIONS -> coordinator.importYouTube(uri)
        YOUTUBE_HISTORY -> coordinator.importYouTubeWatchHistory(uri)
        YOUTUBE_LIKES -> coordinator.importYouTubeLikes(uri)
        YOUTUBE_PLAYLIST -> coordinator.importYouTubePlaylist(uri)
        WATCH_LATER -> coordinator.importYouTubePlaylist(uri, forceWatchLater = true)
        YOUTUBE_MUSIC_PLAYLIST -> coordinator.importYouTubePlaylist(uri, isMusic = true)
        NEWPIPE_SUBSCRIPTIONS -> coordinator.importNewPipe(uri)
        NEWPIPE_HISTORY -> coordinator.importNewPipeWatchHistory(uri)
        NEWPIPE_PLAYLISTS -> coordinator.importNewPipePlaylists(uri)
        LIBRETUBE_SUBSCRIPTIONS -> coordinator.importLibreTube(uri)
        LIBRETUBE_PLAYLISTS -> coordinator.importLibreTubePlaylists(uri)
        FREETUBE_HISTORY -> coordinator.importFreeTubeWatchHistory(uri)
        METROLIST -> coordinator.importMetrolist(uri)
    }
}
