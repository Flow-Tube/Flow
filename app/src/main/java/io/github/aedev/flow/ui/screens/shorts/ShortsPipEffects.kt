package io.github.aedev.flow.ui.screens.shorts

import android.app.Activity
import android.os.Build
import android.util.Log
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.player.PictureInPictureHelper
import io.github.aedev.flow.player.PipQueueNavigation
import io.github.aedev.flow.player.shorts.ShortsPlayerPool
import kotlinx.coroutines.launch

private const val TAG = "ShortsPipEffects"

@Composable
internal fun ShortsPipActionEffect(
    pagerState: PagerState,
    isInPip: Boolean,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val pool = remember { ShortsPlayerPool.getInstance() }
    val scope = rememberCoroutineScope()
    val inPip by rememberUpdatedState(isInPip)
    val preferences = remember(context) { PlayerPreferences(context) }
    val shortsPipEnabled by preferences.shortsPipEnabled.collectAsStateWithLifecycle(initialValue = false)
    val reelPlaying by pool.anyPlaying.collectAsStateWithLifecycle()
    val pipAllowed by rememberUpdatedState(shortsPipEnabled)
    var armed by remember { mutableStateOf(false) }
    val navigation by remember(pagerState) {
        derivedStateOf {
            PipQueueNavigation(
                hasPrevious = pagerState.settledPage > 0,
                hasNext = pagerState.settledPage < pagerState.pageCount - 1,
            )
        }
    }

    fun restateWindow(isPlaying: Boolean) {
        if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        PictureInPictureHelper.updatePipParams(
            activity = activity,
            aspectRatio = pool.activeVideoAspectRatio() ?: PictureInPictureHelper.currentVideoAspectRatio,
            isPlaying = isPlaying,
            autoEnterEnabled = pipAllowed && isPlaying,
            navigation = navigation,
        )
        armed = pipAllowed && isPlaying
    }

    fun step(delta: Int) {
        if (pagerState.pageCount == 0) return
        val target = (pagerState.targetPage + delta).coerceIn(0, pagerState.pageCount - 1)
        if (target != pagerState.targetPage) scope.launch { pagerState.animateScrollToPage(target) }
    }

    // The settled reel starts playing on its own, so the window follows it with a pause button. Setting
    // the params again once the window opens matters too: the Android 17 PiP menu can grey the skip
    // buttons the wrong way round from the params the window entered with, until they are set again.
    // Outside the window, the params are armed while a reel plays so the home gesture enters PiP
    // with the system's animation, as the video player does.
    LaunchedEffect(pagerState.settledPage, navigation, isInPip, shortsPipEnabled, reelPlaying) {
        PictureInPictureHelper.setShortsNavigation(navigation)
        when {
            inPip -> restateWindow(isPlaying = true)
            shortsPipEnabled || armed -> restateWindow(isPlaying = reelPlaying)
        }
    }

    DisposableEffect(activity, pool, pagerState) {
        if (activity == null) return@DisposableEffect onDispose { }

        val receiver =
            PictureInPictureHelper.createPipActionReceiver(
                onPlay = {
                    pool.play()
                    restateWindow(isPlaying = true)
                },
                onPause = {
                    pool.pause()
                    restateWindow(isPlaying = false)
                },
                onPrevious = { step(-1) },
                onNext = { step(1) },
                onClose = { pool.pauseAll() },
            )

        ContextCompat.registerReceiver(
            activity,
            receiver,
            PictureInPictureHelper.getPipIntentFilter(),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        onDispose {
            PictureInPictureHelper.setShortsNavigation(null)
            if (armed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PictureInPictureHelper.updatePipParams(activity, isPlaying = false, autoEnterEnabled = false)
                armed = false
            }
            runCatching { activity.unregisterReceiver(receiver) }
                .onFailure { Log.w(TAG, "Failed to unregister the Shorts PiP receiver", it) }
        }
    }
}
