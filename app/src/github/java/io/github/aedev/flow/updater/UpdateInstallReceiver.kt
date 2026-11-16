package io.github.aedev.flow.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.content.IntentCompat
import dagger.hilt.android.AndroidEntryPoint
import io.github.aedev.flow.data.update.InstallEvent
import javax.inject.Inject

/** Receives the installer session's result: shows the system confirmation, or records a cancel or failure. */
@AndroidEntryPoint
class UpdateInstallReceiver : BroadcastReceiver() {
    @Inject
    lateinit var installer: AppUpdateInstaller

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                installer.onEvent(InstallEvent.NeedsConfirmation)
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)?.let { confirm ->
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Unit
            }

            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                installer.onEvent(InstallEvent.Aborted)
            }

            else -> {
                Log.w(TAG, "Update install failed ($status): ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                installer.onEvent(InstallEvent.Rejected)
            }
        }
    }

    private companion object {
        const val TAG = "UpdateInstallReceiver"
    }
}
