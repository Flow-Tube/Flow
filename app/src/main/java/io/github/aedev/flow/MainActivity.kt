package io.github.aedev.flow

import android.app.PictureInPictureUiState
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.runtime.*
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.github.aedev.flow.applock.AppLock
import io.github.aedev.flow.applock.hideFromRecentsWhenAsked
import io.github.aedev.flow.data.local.AppFontPreferences
import io.github.aedev.flow.data.local.LocalDataManager
import io.github.aedev.flow.data.playlist.PlaylistTransfer
import io.github.aedev.flow.data.recommendation.FlowNeuroEngine
import io.github.aedev.flow.data.repository.DeArrowRepository
import io.github.aedev.flow.data.repository.OriginalTitleRepository
import io.github.aedev.flow.discord.DiscordPresenceRuntime
import io.github.aedev.flow.platform.AppIconController
import io.github.aedev.flow.platform.AppUiRoot
import io.github.aedev.flow.platform.PlayerKeyboardShortcutHelp
import io.github.aedev.flow.player.BackgroundPlaybackPolicy
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.GlobalPlayerState
import io.github.aedev.flow.player.LifecyclePlaybackPreferences
import io.github.aedev.flow.player.MemoryPressurePolicy
import io.github.aedev.flow.player.PictureInPictureHelper
import io.github.aedev.flow.player.PipQueueNavigation
import io.github.aedev.flow.sync2.service.LinkedSyncWorker
import io.github.aedev.flow.ui.IncomingIntents
import io.github.aedev.flow.ui.IncomingLinks
import io.github.aedev.flow.ui.startup.FlowRoot
import io.github.aedev.flow.ui.startup.SplashController
import io.github.aedev.flow.ui.startup.ThemeSettings
import io.github.aedev.flow.ui.startup.themeSettings
import io.github.aedev.flow.utils.AppLanguageManager
import io.github.aedev.flow.utils.FlowCrashHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val PORTRAIT_REEL_ASPECT_RATIO = 9f / 16f

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val links = IncomingLinks()

    @Inject
    lateinit var originalTitles: OriginalTitleRepository

    @Inject
    lateinit var deArrow: DeArrowRepository

    @Inject
    lateinit var lifecyclePlaybackPreferences: LifecyclePlaybackPreferences

    @Inject
    lateinit var appIconController: AppIconController

    @Inject
    lateinit var appFontPreferences: AppFontPreferences

    @Inject
    lateinit var appLock: AppLock

    @Inject
    lateinit var playlistTransfer: dagger.Lazy<PlaylistTransfer>

    @Inject
    lateinit var videoPlayerManager: dagger.Lazy<EnhancedPlayerManager>

    @Inject
    lateinit var localPlayback: dagger.Lazy<io.github.aedev.flow.sync2.live.LocalPlayback>

    @Inject
    lateinit var widgetContentSync: dagger.Lazy<io.github.aedev.flow.widget.core.refresh.WidgetContentSync>

    @Inject
    lateinit var nowPlayingWidgetPublisher: dagger.Lazy<io.github.aedev.flow.widget.nowplaying.NowPlayingWidgetPublisher>

    private val incomingIntents by lazy { IncomingIntents(this, links, playlistTransfer, appLock) }

    private val splashController = SplashController(this)

    private var pipDismissCheckJob: Job? = null
    private var pendingAutoPip = false
    private var cachedAppUiRoot = AppUiRoot.MOBILE

    private fun videoPlaybackStateName(state: Int?): String =
        when (state) {
            androidx.media3.common.Player.STATE_IDLE -> "IDLE"
            androidx.media3.common.Player.STATE_BUFFERING -> "BUFFERING"
            androidx.media3.common.Player.STATE_READY -> "READY"
            androidx.media3.common.Player.STATE_ENDED -> "ENDED"
            null -> "NO_PLAYER"
            else -> "UNKNOWN($state)"
        }

    private fun lifecyclePlaybackSnapshot(): String {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val playerManager =
            io.github.aedev.flow.player.EnhancedPlayerManager
                .getInstance()
        val playerState = playerManager.playerState.value
        val player = playerManager.getPlayer()
        return "interactive=${powerManager?.isInteractive} lifecycle=${lifecycle.currentState} " +
            "pip=$isInPictureInPictureMode pendingAutoPip=$pendingAutoPip " +
            "bgPref=${lifecyclePlaybackPreferences.settings.backgroundPlayEnabled} " +
            "shortsBgPref=${lifecyclePlaybackPreferences.settings.shortsBackgroundPlay} " +
            "explicitBg=${GlobalPlayerState.isExplicitBackgroundPlaybackActive.value} " +
            "video=${playerState.currentVideoId} exo=${videoPlaybackStateName(player?.playbackState)} " +
            "pwr=${player?.playWhenReady} playing=${player?.isPlaying} buffering=${playerState.isBuffering} " +
            "pos=${player?.currentPosition}/${player?.duration} idx=${player?.currentMediaItemIndex} count=${player?.mediaItemCount}"
    }

    private fun videoLifecycleLog(message: String) {
        Log.w("FlowVideoLifecycle", "$message | ${lifecyclePlaybackSnapshot()}")
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguageManager.wrapContext(newBase, AppLanguageManager.resolveLanguageTag(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        splashController.install(installSplashScreen())

        super.onCreate(savedInstanceState)
        DiscordPresenceRuntime.attachActivity(this)

        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)

        enableEdgeToEdge(
            statusBarStyle =
                SystemBarStyle.auto(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT,
                ),
            navigationBarStyle =
                SystemBarStyle.auto(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT,
                ),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
            // Some MIUI builds read force dark from the view rather than the theme (#1517).
            window.decorView.isForceDarkAllowed = false
        }

        // Player setup reads DataStore and opens the media cache index, so it runs off the main
        // thread and settles after the first frame instead of blocking onCreate.
        lifecycleScope.launch { GlobalPlayerState.initializeAsync(applicationContext) }
        // A video another linked device handed over opens as a tapped link would, after any unlock.
        lifecycleScope.launch {
            localPlayback.get().openVideo.collectLatest {
                appLock.awaitUnlocked()
                links.openVideo(it)
            }
        }
        hideFromRecentsWhenAsked(appLock)

        // Snapshot lives in the process, not the activity: onUserLeaveHint/onStop read it
        // synchronously and must still see it after a recreation they run inside of (#817).
        lifecyclePlaybackPreferences.observeIn(lifecycleScope)

        // Initialize Neuro Engine (Recommendation System)
        lifecycleScope.launch(Dispatchers.IO) {
            FlowNeuroEngine.initialize(applicationContext)
        }

        val dataManager = LocalDataManager(applicationContext)

        lifecycleScope.launch {
            io.github.aedev.flow.widget.core.FlowWidgets
                .observeThemeChanges(applicationContext)
        }
        lifecycleScope.launch(Dispatchers.Default) {
            widgetContentSync.get().startIfPlaced()
            nowPlayingWidgetPublisher.get().repairStalePlayback()
        }

        incomingIntents.handleWhenUnlocked(intent, isRestoringState = incomingIntents.isRestoring(savedInstanceState))

        // Read now, alongside the rest of startup, so the theme is usually known by the first composition.
        val storedTheme = MutableStateFlow<ThemeSettings?>(null)
        lifecycleScope.launch { dataManager.themeSettings(appFontPreferences).collect { storedTheme.value = it } }

        setContent {
            FlowRoot(
                storedTheme = storedTheme,
                links = links,
                splashController = splashController,
                originalTitles = originalTitles,
                deArrow = deArrow,
                appIconController = appIconController,
                appLock = appLock,
                onUiRoot = { cachedAppUiRoot = it },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        DiscordPresenceRuntime.setAppForeground(true)
        LinkedSyncWorker.syncOnOpen(this)
    }

    override fun onDestroy() {
        videoLifecycleLog("onDestroy")
        DiscordPresenceRuntime.detachActivity(this)
        val playerManager =
            io.github.aedev.flow.player.EnhancedPlayerManager
                .getInstance()
        val playerState = playerManager.playerState.value
        val hasActiveVideo =
            playerState.currentVideoId != null &&
                (playerState.playWhenReady || playerState.isPlaying || playerState.isBuffering)
        val shouldKeepBackgroundPlayback =
            BackgroundPlaybackPolicy.shouldKeepPlaybackInBackground(
                backgroundPlaybackPreferenceEnabled = lifecyclePlaybackPreferences.settings.backgroundPlayEnabled,
                explicitBackgroundPlaybackActive = GlobalPlayerState.isExplicitBackgroundPlaybackActive.value,
                hasActiveVideo = hasActiveVideo,
            )

        val shouldKeepPausedVideo =
            BackgroundPlaybackPolicy.shouldKeepPausedVideo(
                explicitBackgroundPlaybackActive = GlobalPlayerState.isExplicitBackgroundPlaybackActive.value,
                hasLoadedVideo = playerState.currentVideoId != null,
            )

        if (shouldKeepBackgroundPlayback) {
            handOffVideoPlaybackToBackground()
        } else if (!shouldKeepPausedVideo && !isChangingConfigurations) {
            GlobalPlayerState.release()
        }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingIntents.handleWhenUnlocked(intent, isRestoringState = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        incomingIntents.saveState(outState)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    override fun onPictureInPictureUiStateChanged(pipState: PictureInPictureUiState) {
        super.onPictureInPictureUiStateChanged(pipState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            GlobalPlayerState.setEnteringPip(pipState.isTransitioningToPip)
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        videoLifecycleLog("onPictureInPictureModeChanged pip=$isInPictureInPictureMode")
        GlobalPlayerState.setPipMode(isInPictureInPictureMode)
        appLock.onPictureInPictureChanged(isInPictureInPictureMode)
        pendingAutoPip = false

        clearWindowBrightnessOverride()
        restoreVideoForPipWindow(isInPictureInPictureMode)

        pipDismissCheckJob?.cancel()
        if (!isInPictureInPictureMode) {
            pipDismissCheckJob =
                lifecycleScope.launch {
                    delay(350L)
                    val stillBackgrounded = !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                    val listeningInBackground = GlobalPlayerState.isExplicitBackgroundPlaybackActive.value
                    if (stillBackgrounded && !isInPictureInPictureMode && !listeningInBackground) {
                        // onStop ran while the window was still in PiP, so it skipped its pause (#1152).
                        // Closing the window pauses like leaving the app does, and keeps the video (#1349).
                        if (cachedAppUiRoot == AppUiRoot.MOBILE) {
                            releaseOrientationLock()
                        }
                        pauseVideoForBackground()
                        pauseShortsPlayers()
                    }
                }
        }
    }

    private fun pauseShortsPlayers() {
        io.github.aedev.flow.player.shorts.ShortsPlayerPool
            .getInstance()
            .pauseAll()
    }

    private fun restoreVideoForPipWindow(isInPictureInPictureMode: Boolean) {
        val playerManager = videoPlayerManager.get()
        if (
            MemoryPressurePolicy.shouldRestoreVideoOnPipEntry(
                isInPictureInPictureMode = isInPictureInPictureMode,
                isAudioOnly = playerManager.isInAudioOnlyMode(),
                isVideoRestorePending = playerManager.isVideoSurfaceRestorePending(),
                explicitBackgroundPlaybackActive = GlobalPlayerState.isExplicitBackgroundPlaybackActive.value,
            )
        ) {
            videoLifecycleLog("restoreVideoOutput for PiP window")
            playerManager.restoreVideoOutput()
        }
    }

    private fun releaseOrientationLock() {
        if (requestedOrientation != android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun clearWindowBrightnessOverride() {
        val layoutParams = window.attributes
        if (layoutParams.screenBrightness != android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
            layoutParams.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = layoutParams
        }
    }

    override fun onResume() {
        super.onResume()
        FlowCrashHandler.recordPhase("activity", "onResume pip=$isInPictureInPictureMode")
        videoLifecycleLog("onResume")
        // GlobalPlayerState outlives this Activity, so an instance destroyed straight out of PiP
        // without an onPictureInPictureModeChanged(false) would leave the flag latched true for
        // the rest of the process. Re-reading the real value here is the only reset path.
        GlobalPlayerState.setPipMode(isInPictureInPictureMode)
        pendingAutoPip = false
        pipDismissCheckJob?.cancel()
        PictureInPictureHelper.dismissPopup(this)
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            io.github.aedev.flow.player.PlayerHardwareController.fullscreenVideoActive.value &&
            io.github.aedev.flow.player.PlayerHardwareController
                .adjustVolume(this, raise = keyCode == KeyEvent.KEYCODE_VOLUME_UP)
        ) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onProvideKeyboardShortcuts(
        data: MutableList<KeyboardShortcutGroup>,
        menu: Menu?,
        deviceId: Int,
    ) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        data += PlayerKeyboardShortcutHelp.group(this)
    }

    override fun onKeyUp(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            io.github.aedev.flow.player.PlayerHardwareController.fullscreenVideoActive.value
        ) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onStop() {
        DiscordPresenceRuntime.setAppForeground(false)
        super.onStop()
        FlowCrashHandler.recordPhase(
            "activity",
            "onStop pip=$isInPictureInPictureMode " +
                "backgroundPlay=${lifecyclePlaybackPreferences.settings.backgroundPlayEnabled} " +
                "shortsBackground=${lifecyclePlaybackPreferences.settings.shortsBackgroundPlay}",
        )
        videoLifecycleLog("onStop")
        if (!isInPictureInPictureMode && !PictureInPictureHelper.isPopupActive) {
            if (cachedAppUiRoot == AppUiRoot.MOBILE) {
                releaseOrientationLock()
            }
            if (!lifecyclePlaybackPreferences.settings.shortsBackgroundPlay) {
                pauseShortsPlayers()
            }

            if (pendingAutoPip) {
                lifecycleScope.launch {
                    delay(800L)
                    if (
                        pendingAutoPip &&
                        !isInPictureInPictureMode &&
                        !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                    ) {
                        pendingAutoPip = false
                        handleBackgroundPlaybackOnStop()
                    }
                }
            } else {
                handleBackgroundPlaybackOnStop()
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (cachedAppUiRoot == AppUiRoot.TV) return
        val explicitBackgroundPlaybackActive =
            GlobalPlayerState.isExplicitBackgroundPlaybackActive.value
        FlowCrashHandler.recordPhase(
            "activity",
            "onUserLeaveHint autoPip=${lifecyclePlaybackPreferences.settings.autoPipEnabled} " +
                "explicitBackground=$explicitBackgroundPlaybackActive",
        )
        videoLifecycleLog("onUserLeaveHint")
        // Only enter PiP mode if video is playing and has progressed
        // We use the EnhancedPlayerManager directly to get the immediate state
        val playerManager =
            io.github.aedev.flow.player.EnhancedPlayerManager
                .getInstance()
        val musicManager = io.github.aedev.flow.player.EnhancedMusicPlayerManager

        val isVideoPlaying =
            playerManager.playerState.value.isPlaying &&
                playerManager.playerState.value.currentVideoId != null &&
                playerManager.getCurrentPosition() > 500 // At least 0.5s in
        val isMusicPlaying = musicManager.playerState.value.isPlaying

        // Only enter PiP for video, not for music (which uses background service)
        val shouldEnterAutoPip =
            BackgroundPlaybackPolicy.shouldEnterAutoPip(
                autoPipEnabled = lifecyclePlaybackPreferences.settings.autoPipEnabled,
                isVideoPlaying = isVideoPlaying,
                explicitBackgroundPlaybackActive = explicitBackgroundPlaybackActive,
                soundOnlyBackground = GlobalPlayerState.isVideoSoundOnly.value,
            )
        if (shouldEnterAutoPip && !isMusicPlaying) {
            if (PictureInPictureHelper.systemEntersPipOnLeave()) {
                pendingAutoPip = true
                return
            }
            enterPlayerPictureInPictureMode(
                aspectRatio = PictureInPictureHelper.currentVideoAspectRatio,
                isPlaying = true,
            )
            return
        }

        if (!lifecyclePlaybackPreferences.settings.shortsPipEnabled || isMusicPlaying) return
        val shortsPool =
            io.github.aedev.flow.player.shorts.ShortsPlayerPool
                .getInstance()
        if (!shortsPool.isPlaying()) return
        enterPlayerPictureInPictureMode(
            aspectRatio = shortsPool.activeVideoAspectRatio() ?: PORTRAIT_REEL_ASPECT_RATIO,
            isPlaying = true,
            navigation = PictureInPictureHelper.shortsNavigation,
        )
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        FlowCrashHandler.recordPhase("memory", "MainActivity.onTrimMemory level=$level")
        videoPlayerManager.get().handleMemoryPressure(
            trimLevel = level,
            videoVisible = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || isInPictureInPictureMode,
        )
    }

    fun enterPlayerPictureInPictureMode(
        aspectRatio: Float = PictureInPictureHelper.currentVideoAspectRatio,
        isPlaying: Boolean = true,
        openSettingsOnDenied: Boolean = false,
        navigation: PipQueueNavigation? = null,
    ): Boolean {
        if (cachedAppUiRoot == AppUiRoot.TV) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (!PictureInPictureHelper.isPipAllowed(this)) {
            if (openSettingsOnDenied) {
                PictureInPictureHelper.openPipSettings(this)
            }
            return false
        }

        pendingAutoPip = true
        val entered =
            PictureInPictureHelper.enterPipMode(
                activity = this,
                aspectRatio = aspectRatio,
                isPlaying = isPlaying,
                autoEnterEnabled = PictureInPictureHelper.isAutoEnterArmed,
                navigation = navigation,
                offerBackground = navigation == null,
            )
        if (!entered) {
            pendingAutoPip = false
        }
        return entered
    }

    private fun handOffVideoPlaybackToBackground() {
        FlowCrashHandler.recordPhase("background-handoff", "handOffVideoPlaybackToBackground")
        videoLifecycleLog("handOffVideoPlaybackToBackground")
        val playerManager =
            io.github.aedev.flow.player.EnhancedPlayerManager
                .getInstance()
        val playerState = playerManager.playerState.value
        if (
            playerState.currentVideoId != null &&
            (playerState.playWhenReady || playerState.isPlaying || playerState.isBuffering)
        ) {
            val video = GlobalPlayerState.currentVideo.value
            playerManager.startBackgroundService(
                videoId = video?.id ?: playerState.currentVideoId,
                title = video?.title?.ifEmpty { "Playing..." } ?: "Playing...",
                channel = video?.channelName ?: "",
                thumbnail = video?.thumbnailUrl ?: "",
            )
            playerManager.continueVideoPlaybackInBackground()
        }
    }

    private fun handleBackgroundPlaybackOnStop() {
        FlowCrashHandler.recordPhase("background-handoff", "handleBackgroundPlaybackOnStop")
        videoLifecycleLog("handleBackgroundPlaybackOnStop")
        val playerManager =
            io.github.aedev.flow.player.EnhancedPlayerManager
                .getInstance()
        val playerState = playerManager.playerState.value
        val hasActiveVideo =
            playerState.currentVideoId != null &&
                (playerState.playWhenReady || playerState.isPlaying || playerState.isBuffering)

        if (!hasActiveVideo) return

        val shouldKeepBackgroundPlayback =
            BackgroundPlaybackPolicy.shouldKeepPlaybackInBackground(
                backgroundPlaybackPreferenceEnabled = lifecyclePlaybackPreferences.settings.backgroundPlayEnabled,
                explicitBackgroundPlaybackActive = GlobalPlayerState.isExplicitBackgroundPlaybackActive.value,
                hasActiveVideo = hasActiveVideo,
            )

        if (shouldKeepBackgroundPlayback) {
            videoLifecycleLog("handleBackgroundPlaybackOnStop handoff")
            handOffVideoPlaybackToBackground()
        } else {
            videoLifecycleLog("handleBackgroundPlaybackOnStop pause")
            pauseVideoForBackground()
        }
    }

    private fun pauseVideoForBackground() {
        val playerManager =
            io.github.aedev.flow.player.EnhancedPlayerManager
                .getInstance()
        playerManager.pause()
        playerManager.parkVideoForBackground()
        playerManager.stopBackgroundService()
    }

    companion object {
        const val EXTRA_BENCHMARK_BYPASS_ONBOARDING = "io.github.aedev.flow.extra.BENCHMARK_BYPASS_ONBOARDING"
    }
}
