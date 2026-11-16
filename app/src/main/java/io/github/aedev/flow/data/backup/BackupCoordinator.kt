package io.github.aedev.flow.data.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.BackupRepository
import io.github.aedev.flow.data.local.LocalDataManager
import io.github.aedev.flow.data.local.NO_LIKES
import io.github.aedev.flow.data.playlist.PlaylistTransfer
import io.github.aedev.flow.data.profile.ProfileRegistry
import io.github.aedev.flow.data.profile.ProfileSlices
import io.github.aedev.flow.data.profile.TooManyProfiles
import io.github.aedev.flow.data.recommendation.FlowNeuroEngine
import io.github.aedev.flow.data.recommendation.music.MusicBrainEngine
import io.github.aedev.flow.notification.NotificationHelper
import io.github.aedev.flow.sync2.service.LinkedReset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** What the one running backup operation is doing, for the screens and the notification to show. */
sealed interface BackupOperation {
    data object Idle : BackupOperation

    /**
     * [headline] is what to show ("Importing NewPipe subscriptions…"); [current]/[total] are 0/0
     * while the work has no measurable progress yet.
     */
    data class Running(
        val headline: String,
        val current: Int,
        val total: Int,
    ) : BackupOperation

    data class Succeeded(
        val message: String,
    ) : BackupOperation

    data class Failed(
        val message: String,
    ) : BackupOperation

    /** Waiting for a yes before going on: [message] says what will happen. */
    data class Confirming(
        val message: String,
    ) : BackupOperation
}

/**
 * Runs every import and export, one at a time, on a scope of its own — so an import or export keeps
 * going when the screen that started it closes, and onboarding and Settings watch the same
 * operation. Results arrive as ready-to-show messages. Every start returns false when another
 * operation is still running, for the caller to say so.
 */
@Singleton
class BackupCoordinator
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val repository: BackupRepository,
        private val musicBrain: MusicBrainEngine,
        private val localDataManager: LocalDataManager,
        private val linkedReset: LinkedReset,
        private val slices: ProfileSlices,
        private val playlistTransfer: PlaylistTransfer,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val _operation = MutableStateFlow<BackupOperation>(BackupOperation.Idle)
        val operation: StateFlow<BackupOperation> = _operation.asStateFlow()

        val isRunning: Boolean get() = _operation.value is BackupOperation.Running

        private var confirmed: (() -> Boolean)? = null

        fun dismiss() {
            if (!isRunning) _operation.value = BackupOperation.Idle
        }

        fun exportAppData(uri: Uri) =
            exportTo(uri, R.string.settings_export_success, R.string.settings_export_failed) { repository.exportData(uri) }

        fun exportNewPipeSubscriptions(uri: Uri) =
            exportTo(uri, R.string.export_newpipe_subs_failed) {
                repository.exportSubscriptionsAsNewPipe(uri).map { export ->
                    if (export.skipped > 0) {
                        context.resources.getQuantityString(R.plurals.export_newpipe_subs_skipped, export.skipped, export.skipped)
                    } else {
                        context.getString(R.string.export_newpipe_subs_success)
                    }
                }
            }

        fun exportWatchHistory(uri: Uri) =
            exportTo(uri, R.string.settings_export_success, R.string.history_export_failed) {
                repository.exportWatchHistory(uri)
            }

        fun exportEngine(uri: Uri) =
            exportTo(uri, R.string.export_engine_success, R.string.export_engine_failed) {
                writeStream(uri) { out -> FlowNeuroEngine.exportBrainToStream(out) }
            }

        fun exportMusicBrain(uri: Uri) =
            exportTo(uri, R.string.music_brain_export_success, R.string.music_brain_export_failed) {
                writeStream(uri) { out -> musicBrain.exportBrainToStream(out).let { true } }
            }

        fun exportPlaylists(uri: Uri) =
            exportTo(uri, R.string.playlists_export_failed) {
                val count = playlistTransfer.writeLibraryTo(uri)
                if (count == null) {
                    Result.failure(IOException("Playlists not written"))
                } else {
                    Result.success(context.resources.getQuantityString(R.plurals.playlists_exported, count, count))
                }
            }

        fun exportMaster(uri: Uri) = exportTo(uri, R.string.master_backup_export_failed) { slices.exportMaster(uri).map(::masterMessage) }

        /** Writes the automatic backup now, into the chosen folder, and records when it ran. */
        fun backUpNow(
            type: LocalDataManager.AutoBackupType,
            folder: Uri,
        ) = run(context.getString(R.string.auto_backup_run_now), notify = false) {
            val result =
                when (type) {
                    LocalDataManager.AutoBackupType.APP_DATA -> {
                        repository.exportDataToFolder(folder)
                    }

                    LocalDataManager.AutoBackupType.BRAIN -> {
                        repository.exportBrainToFolder(folder)
                    }

                    LocalDataManager.AutoBackupType.MASTER -> {
                        slices.exportMasterToFolder(folder)
                    }
                }
            if (result.isSuccess) {
                localDataManager.setAutoBackupLastRun(System.currentTimeMillis())
                BackupOperation.Succeeded(context.getString(R.string.auto_backup_success))
            } else {
                BackupOperation.Failed(context.getString(R.string.auto_backup_failed))
            }
        }

        fun importFlowBackup(uri: Uri) =
            run(context.getString(R.string.import_flow_backup_item_title)) {
                val result = repository.importData(uri)
                if (result.isSuccess) resetSyncLedgers()
                result.fold(
                    onSuccess = { BackupOperation.Succeeded(context.getString(R.string.import_flow_backup_success)) },
                    onFailure = { failed(R.string.import_flow_backup_failed_template, it) },
                )
            }

        /** A backup from before profiles is confirmed first when there is more than one profile it could go into. */
        fun importMaster(uri: Uri) =
            run(context.getString(R.string.master_backup_title)) {
                val target = slices.oldMasterTarget(uri)
                if (target == null) {
                    restoreMaster(uri)
                } else {
                    confirmed = { run(context.getString(R.string.master_backup_title)) { restoreMaster(uri) } }
                    BackupOperation.Confirming(context.getString(R.string.master_restore_old_into, target))
                }
            }

        fun confirm() {
            val action = confirmed ?: return
            confirmed = null
            _operation.value = BackupOperation.Idle
            action()
        }

        fun cancelConfirmation() {
            confirmed = null
            if (_operation.value is BackupOperation.Confirming) _operation.value = BackupOperation.Idle
        }

        private suspend fun restoreMaster(uri: Uri): BackupOperation =
            slices.restoreMaster(uri).fold(
                onSuccess = { restored ->
                    if (restored.restoredNow) resetSyncLedgers()
                    val message =
                        if (restored.waiting.isEmpty()) {
                            context.getString(R.string.import_master_backup_success)
                        } else {
                            context.resources.getQuantityString(
                                R.plurals.master_restore_waiting,
                                restored.waiting.size,
                                restored.waiting.joinToString(),
                            )
                        }
                    BackupOperation.Succeeded(message)
                },
                onFailure = {
                    if (it is TooManyProfiles) {
                        BackupOperation.Failed(context.getString(R.string.master_restore_too_many, ProfileRegistry.MAX_PROFILES))
                    } else {
                        failed(R.string.import_failed_template, it)
                    }
                },
            )

        fun importEngine(uri: Uri) =
            run(context.getString(R.string.import_engine_data)) {
                val ok = readStream(uri) { input -> FlowNeuroEngine.importBrainFromStream(context, input) } == true
                if (ok) {
                    BackupOperation.Succeeded(context.getString(R.string.import_engine_success))
                } else {
                    BackupOperation.Failed(context.getString(R.string.import_engine_failed))
                }
            }

        fun importMusicBrain(uri: Uri) =
            run(context.getString(R.string.import_music_brain_title)) {
                val ok = readStream(uri) { input -> musicBrain.importBrainFromStream(input).let { true } } == true
                if (ok) {
                    BackupOperation.Succeeded(context.getString(R.string.music_brain_import_success))
                } else {
                    BackupOperation.Failed(context.getString(R.string.music_brain_import_failed))
                }
            }

        fun importNewPipe(uri: Uri) =
            importCounted(R.string.import_label_newpipe_subscriptions) { progress -> repository.importNewPipe(uri, progress) }

        /** [importNewPipe] for a screen that shows the outcome itself, so it is taken off the shared state. */
        suspend fun importNewPipeForResult(uri: Uri): String {
            if (!importNewPipe(uri)) return context.getString(R.string.backup_busy)
            val outcome = operation.first { it !is BackupOperation.Running }
            dismiss()
            return when (outcome) {
                is BackupOperation.Succeeded -> outcome.message
                is BackupOperation.Failed -> outcome.message
                else -> ""
            }
        }

        fun importYouTube(uri: Uri) =
            importCounted(R.string.import_label_youtube_subscriptions) { progress -> repository.importYouTube(uri, progress) }

        fun importLibreTube(uri: Uri) =
            importCounted(R.string.import_label_libretube_subscriptions) { progress -> repository.importLibreTube(uri, progress) }

        fun importYouTubeWatchHistory(uri: Uri) =
            importCounted(R.string.import_label_youtube_watch_history) {
                repository.importYouTubeWatchHistory(uri)
            }

        fun importYouTubeLikes(uri: Uri) = importCounted(R.string.import_label_youtube_likes) { repository.importYouTubeLikes(uri) }

        fun importFreeTubeWatchHistory(uri: Uri) =
            importCounted(R.string.import_label_freetube_watch_history) {
                repository.importYouTubeWatchHistory(uri)
            }

        fun importNewPipeWatchHistory(uri: Uri) =
            importCounted(R.string.import_label_newpipe_watch_history) {
                repository.importNewPipeWatchHistory(uri)
            }

        fun importMetrolist(uri: Uri) =
            importCounted(R.string.import_label_metrolist_playlists) { progress -> repository.importMetrolist(uri, progress) }

        fun importNewPipePlaylists(uri: Uri) =
            importCounted(R.string.import_label_newpipe_playlists) { progress -> repository.importNewPipePlaylists(uri, progress) }

        fun importLibreTubePlaylists(uri: Uri) =
            importCounted(R.string.import_label_libretube_playlists) { progress -> repository.importLibreTubePlaylists(uri, progress) }

        /** Every archive of one export; Google splits large exports into several. */
        fun importYouTubeTakeout(uris: List<Uri>): Boolean {
            val label = context.getString(R.string.import_label_youtube_takeout)
            return run(label) {
                repository
                    .importYouTubeTakeout(uris) { step, current, total -> progress("$label – $step", current, total) }
                    .fold(
                        onSuccess = { summary ->
                            BackupOperation.Succeeded(summary.ifBlank { context.getString(R.string.import_success, label) })
                        },
                        onFailure = { failed(R.string.import_failed_template, it) },
                    )
            }
        }

        fun importYouTubePlaylist(
            uri: Uri,
            isMusic: Boolean = false,
            forceWatchLater: Boolean = false,
        ) = run(context.getString(R.string.import_yt_playlist)) {
            repository.importYouTubePlaylist(uri, isMusic, forceWatchLater).fold(
                onSuccess = { (name, count) ->
                    BackupOperation.Succeeded(
                        context.resources.getQuantityString(R.plurals.import_yt_playlist_success_template, count, name, count),
                    )
                },
                onFailure = { error ->
                    if (error.message == NO_VIDEOS) {
                        BackupOperation.Failed(context.getString(R.string.import_yt_playlist_empty_error))
                    } else {
                        failed(R.string.import_yt_playlist_failed_template, error)
                    }
                },
            )
        }

        private fun importCounted(
            labelRes: Int,
            import: suspend (progress: (Int, Int) -> Unit) -> Result<Int>,
        ): Boolean {
            val label = context.getString(labelRes)
            return run(label) {
                import { current, total -> progress(label, current, total) }.fold(
                    onSuccess = { count ->
                        BackupOperation.Succeeded(
                            if (count > 0) {
                                context.getString(R.string.import_success_count, count, label)
                            } else {
                                context.getString(R.string.import_success, label)
                            },
                        )
                    },
                    onFailure = { error -> BackupOperation.Failed(importErrorMessage(error)) },
                )
            }
        }

        private fun exportTo(
            uri: Uri,
            successRes: Int,
            failureRes: Int,
            export: suspend () -> Result<Unit>,
        ) = exportTo(uri, failureRes) { export().map { context.getString(successRes) } }

        /**
         * The save dialog has already created [uri] by the time this runs, so a busy or failed export
         * deletes it rather than leave an empty file behind that looks like a finished export.
         */
        private fun exportTo(
            uri: Uri,
            failureRes: Int,
            export: suspend () -> Result<String>,
        ): Boolean {
            val started =
                run(context.getString(R.string.settings_backup_exporting), notify = false) {
                    val result =
                        try {
                            export()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Result.failure(e)
                        }
                    result.fold(
                        onSuccess = { BackupOperation.Succeeded(it) },
                        onFailure = {
                            withContext(Dispatchers.IO) { discard(uri) }
                            BackupOperation.Failed(context.getString(failureRes))
                        },
                    )
                }
            if (!started) scope.launch(Dispatchers.IO) { discard(uri) }
            return started
        }

        /** A profile without a slice yet has never been left since this build, so its data isn't in the file. */
        private fun masterMessage(missing: List<String>): String =
            if (missing.isEmpty()) {
                context.getString(R.string.master_backup_export_success)
            } else {
                context.getString(R.string.master_backup_export_missing, missing.joinToString())
            }

        private fun discard(uri: Uri) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
        }

        /** Runs [work] unless another operation is already running; the result replaces the progress. */
        private fun run(
            label: String,
            notify: Boolean = true,
            work: suspend () -> BackupOperation,
        ): Boolean {
            if (isRunning) return false
            progress(label, 0, 0, notify)
            scope.launch {
                val outcome =
                    try {
                        work()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed(R.string.import_failed_template, e)
                    }
                NotificationHelper.cancelImportNotification(context)
                if (notify && outcome is BackupOperation.Succeeded && NotificationHelper.hasNotificationPermission(context)) {
                    NotificationHelper.showImportComplete(context, label, 0, outcome.message)
                }
                _operation.value = outcome
            }
            return true
        }

        private fun progress(
            label: String,
            current: Int,
            total: Int,
            notify: Boolean = true,
        ) {
            val headline = if (notify) context.getString(R.string.importing_label, label) else label
            _operation.value = BackupOperation.Running(headline, current, total)
            if (notify && NotificationHelper.hasNotificationPermission(context)) {
                NotificationHelper.showImportProgress(context, label, current, total)
            }
        }

        private fun failed(
            templateRes: Int,
            error: Throwable,
        ) = BackupOperation.Failed(context.getString(templateRes, error.message ?: context.getString(R.string.unknown_error)))

        private fun importErrorMessage(error: Throwable): String =
            when (error.message) {
                "no_entries" -> context.getString(R.string.import_no_history_entries)
                NO_VIDEOS -> context.getString(R.string.import_no_videos)
                NO_LIKES -> context.getString(R.string.import_no_likes)
                "no_content" -> context.getString(R.string.import_no_content)
                "invalid_format" -> context.getString(R.string.import_invalid_format)
                else -> context.getString(R.string.import_failed_template, error.message ?: context.getString(R.string.unknown_error))
            }

        private suspend fun resetSyncLedgers() = linkedReset.dataReplaced()

        private suspend fun writeStream(
            uri: Uri,
            write: suspend (java.io.OutputStream) -> Boolean,
        ): Result<Unit> =
            withContext(Dispatchers.IO) {
                val stream =
                    context.contentResolver.openOutputStream(uri, "wt") ?: return@withContext Result.failure(IllegalStateException())
                val ok = stream.use { write(it) }
                if (ok) Result.success(Unit) else Result.failure(IllegalStateException())
            }

        private suspend fun <T> readStream(
            uri: Uri,
            read: suspend (java.io.InputStream) -> T,
        ): T? =
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { read(it) }
            }

        private companion object {
            const val NO_VIDEOS = "no_videos"
        }
    }
