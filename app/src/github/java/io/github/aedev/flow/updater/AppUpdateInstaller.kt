package io.github.aedev.flow.updater

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.update.AppRelease
import io.github.aedev.flow.data.update.InstallEvent
import io.github.aedev.flow.data.update.InstallPhase
import io.github.aedev.flow.data.update.UpdateDownload
import io.github.aedev.flow.data.update.UpdateFailure
import io.github.aedev.flow.data.update.on
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val WORK_NAME = "app_update_download"
private const val INSTALL_REQUEST = 7301
private const val TAG = "AppUpdateInstaller"

/**
 * Downloads a release in the background and installs it through a platform installer session.
 * Android still asks the user to confirm, and refuses any APK not signed with Flow's key.
 */
@Singleton
class AppUpdateInstaller
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val _installPhase = MutableStateFlow<InstallPhase>(InstallPhase.Idle)
        val installPhase: StateFlow<InstallPhase> = _installPhase.asStateFlow()

        private val packageInstaller get() = context.packageManager.packageInstaller
        private val mainHandler = Handler(Looper.getMainLooper())

        @Volatile private var sessionId: Int? = null

        @Volatile private var sessionCallback: PackageInstaller.SessionCallback? = null

        val isAvailable: Boolean = true

        fun download(release: AppRelease) {
            val apk = release.apk ?: return
            UpdateFiles.clean(context, keepVersion = null)
            val request =
                OneTimeWorkRequestBuilder<UpdateDownloadWorker>()
                    .setInputData(
                        workDataOf(
                            UpdateDownloadWorker.KEY_URL to apk.url,
                            UpdateDownloadWorker.KEY_SHA256 to apk.sha256,
                            UpdateDownloadWorker.KEY_VERSION to release.version,
                        ),
                    ).addTag(tagFor(release.version))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                    .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel() {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        fun state(version: String): Flow<UpdateDownload> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORK_NAME).map { infos ->
                val info = infos.lastOrNull { tagFor(version) in it.tags }
                val ready = UpdateFiles.apkFile(context, version).exists()
                when (info?.state) {
                    WorkInfo.State.RUNNING -> {
                        if (info.progress.getString(UpdateDownloadWorker.KEY_PHASE) == UpdateDownloadWorker.PHASE_VERIFY) {
                            UpdateDownload.Verifying
                        } else {
                            UpdateDownload.Running(info.progress.getFloat(UpdateDownloadWorker.KEY_PROGRESS, -1f).takeIf { it >= 0f })
                        }
                    }

                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                        UpdateDownload.Running(null)
                    }

                    WorkInfo.State.FAILED -> {
                        val reason = info.outputData.getString(UpdateDownloadWorker.KEY_FAILURE)
                        UpdateDownload.Failed(UpdateFailure.entries.firstOrNull { it.name == reason } ?: UpdateFailure.NETWORK)
                    }

                    else -> {
                        if (ready) UpdateDownload.Ready else UpdateDownload.Idle
                    }
                }
            }

        fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

        fun installPermissionIntent(): Intent =
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

        /** Hands the verified APK to the system installer; [installPhase] follows the session from here. */
        suspend fun install(version: String) {
            onEvent(InstallEvent.Started)
            withContext(Dispatchers.IO) {
                val apk = UpdateFiles.apkFile(context, version)
                if (!apk.exists()) {
                    onEvent(InstallEvent.Aborted)
                    return@withContext
                }
                val params =
                    PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                        setAppPackageName(context.packageName)
                        setSize(apk.length())
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                        }
                    }
                var id: Int? = null
                try {
                    id = packageInstaller.createSession(params).also { watch(it) }
                    packageInstaller.openSession(id).use { session ->
                        session.openWrite(apk.name, 0, apk.length()).use { output ->
                            apk.inputStream().use { it.copyTo(output) }
                            session.fsync(output)
                        }
                        val callback =
                            PendingIntent.getBroadcast(
                                context,
                                INSTALL_REQUEST,
                                Intent(context, UpdateInstallReceiver::class.java),
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                            )
                        session.commit(callback.intentSender)
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "Could not hand the update to the installer", e)
                    id?.let { runCatching { packageInstaller.abandonSession(it) } }
                    unwatch()
                    onEvent(InstallEvent.Rejected)
                }
            }
        }

        /** The confirmation pauses Flow, so a resume alone says nothing about whether the install is done. */
        fun reconcile() {
            val id = sessionId ?: return
            val exists = packageInstaller.getSessionInfo(id) != null
            if (!exists) unwatch()
            onEvent(InstallEvent.Resumed(exists))
        }

        /** Forgets downloads for anything but [keepVersion], e.g. once an update has been installed. */
        fun clean(keepVersion: String?) = UpdateFiles.clean(context, keepVersion)

        internal fun onEvent(event: InstallEvent) {
            _installPhase.update { it.on(event) }
        }

        private fun watch(id: Int) {
            unwatch()
            sessionId = id
            val callback =
                object : PackageInstaller.SessionCallback() {
                    override fun onCreated(sessionId: Int) = Unit

                    override fun onBadgingChanged(sessionId: Int) = Unit

                    override fun onActiveChanged(
                        sessionId: Int,
                        active: Boolean,
                    ) = Unit

                    override fun onProgressChanged(
                        sessionId: Int,
                        progress: Float,
                    ) {
                        if (sessionId == this@AppUpdateInstaller.sessionId) onEvent(InstallEvent.Progress(progress))
                    }

                    // The outcome itself comes from UpdateInstallReceiver, which can tell a cancel from a failure.
                    override fun onFinished(
                        sessionId: Int,
                        success: Boolean,
                    ) {
                        if (sessionId == this@AppUpdateInstaller.sessionId) unwatch()
                    }
                }
            sessionCallback = callback
            packageInstaller.registerSessionCallback(callback, mainHandler)
        }

        private fun unwatch() {
            sessionCallback?.let { packageInstaller.unregisterSessionCallback(it) }
            sessionCallback = null
            sessionId = null
        }

        private fun tagFor(version: String) = "app-update-$version"
    }
