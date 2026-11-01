package io.github.aedev.flow.data.profile

import android.app.ActivityOptions
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.window.SplashScreen
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.backup.BackupCoordinator
import io.github.aedev.flow.data.recommendation.FlowNeuroEngine
import io.github.aedev.flow.data.recommendation.music.MusicBrainEngine
import io.github.aedev.flow.data.scrobble.Scrobbler
import io.github.aedev.flow.data.stats.VideoStatsRecorder
import io.github.aedev.flow.discord.DiscordPresenceController
import io.github.aedev.flow.notification.NotificationHelper
import io.github.aedev.flow.notification.WellbeingAlarms
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.player.MusicResumeTracker
import io.github.aedev.flow.player.PlaybackShutdown
import io.github.aedev.flow.service.MusicServiceState
import io.github.aedev.flow.sync.SyncManager
import io.github.aedev.flow.sync2.service.LinkedSync
import io.github.aedev.flow.utils.potoken.PoTokenGenerator
import io.github.aedev.flow.widget.core.state.clearNowPlayingSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject

/** The switch steps on a device. It only calls the release and save paths the app already has. */
class AppProfileSwitchSteps
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val musicResumeTracker: MusicResumeTracker,
        private val scrobbler: Scrobbler,
        private val musicBrainEngine: MusicBrainEngine,
        private val neuroEngine: FlowNeuroEngine,
        private val videoStats: VideoStatsRecorder,
        private val sync: dagger.Lazy<SyncManager>,
        private val linked: dagger.Lazy<LinkedSync>,
        private val backups: dagger.Lazy<BackupCoordinator>,
        private val discord: DiscordPresenceController,
        private val slices: dagger.Lazy<ProfileSlices>,
    ) : ProfileSwitchSteps {
        private val registry: ProfileRegistry get() = ActiveProfile.registry(context)

        override suspend fun refusal(target: String): SwitchRefusal? =
            when {
                target == ActiveProfile.get(context).id -> SwitchRefusal.ALREADY_ACTIVE

                // The car shows this profile's library and would drop its connection mid-drive.
                MusicServiceState.isCarConnected -> SwitchRefusal.CAR_CONNECTED

                // Both write this profile's data until they finish; the restart would cut them off halfway.
                sync.get().isBusy || linked.get().syncing.value -> SwitchRefusal.SYNC_RUNNING

                backups.get().isRunning -> SwitchRefusal.BACKUP_RUNNING

                registry.state
                    .first()
                    .profiles
                    .none { it.id == target } -> SwitchRefusal.UNKNOWN_PROFILE

                else -> null
            }

        private var gateClosedAt = 0L

        override fun closeGate(target: String) {
            gateClosedAt = SystemClock.elapsedRealtime()
            ProfileSwitchGate.close(target)
        }

        override fun reopenGate() {
            ProfileSwitchGate.reopen()
            SwitchIntentQueue.drop()
        }

        override fun finishOpenSessions() = BeforeProfileSwitch.run()

        override suspend fun saveQueue() = EnhancedMusicPlayerManager.persistQueueNow()

        override suspend fun stopPlayback() = PlaybackShutdown.stopForSwitch(context)

        // The music brain goes after the service has stopped: stopping finalizes the listen session it learns from.
        override suspend fun awaitWrites() {
            musicResumeTracker.awaitWrites()
            scrobbler.awaitWrites()
            musicBrainEngine.awaitWrites()
            neuroEngine.awaitWrites()
            videoStats.awaitWrites()
        }

        override suspend fun writeSlice() = slices.get().writeSwitchSlice()

        override suspend fun cancelProfileWork() {
            ProfileWork.cancelAll(context)
            WellbeingAlarms.cancel(context)
            NotificationHelper.cancelProfileNotifications(context)
            // The Now Playing widgets would keep this profile's last track until the next one plays something.
            context.clearNowPlayingSnapshot()
            // Discord would show this profile's last track until its gateway noticed the process was gone.
            withTimeoutOrNull(DISCORD_TIMEOUT_MS) { discord.endForSwitch() }
        }

        override suspend fun clearCaches() {
            // InnerTube's cache may hold search suggestions and visitor responses; the share folders, exports.
            listOf("innertube_http_cache", "recap", "playlists").forEach { File(context.cacheDir, it).deleteRecursively() }
            if (ProfileWebView.clearedAtSwitch) PoTokenGenerator.resetSession()
        }

        override suspend fun persistActive(target: String) {
            registry.setActive(target)
        }

        override suspend fun restart() {
            // The switching screen stays long enough to be read rather than flash.
            delay(MIN_SWITCHING_SCREEN_MS - (SystemClock.elapsedRealtime() - gateClosedAt))
            val relaunch = SwitchIntentQueue.relaunchIntent(context)
            // Measured in PR 2.0: a start that does not come from the launcher gets a splash without the icon.
            val options =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ActivityOptions.makeBasic().setSplashScreenStyle(SplashScreen.SPLASH_SCREEN_STYLE_ICON).toBundle()
                } else {
                    null
                }
            context.startActivity(relaunch, options)
            Runtime.getRuntime().exit(0)
        }
    }

private const val DISCORD_TIMEOUT_MS = 2_000L

private const val MIN_SWITCHING_SCREEN_MS = 500L
