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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
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
    val activity = LocalContext.current as? Activity
    val pool = remember { ShortsPlayerPool.getInstance() }
    val scope = rememberCoroutineScope()
    val inPip by rememberUpdatedState(isInPip)
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
            navigation = navigation,
        )
    }

    fun step(delta: Int) {
        if (pagerState.pageCount == 0) return
        val target = (pagerState.targetPage + delta).coerceIn(0, pagerState.pageCount - 1)
        if (target != pagerState.targetPage) scope.launch { pagerState.animateScrollToPage(target) }
    }

    // The settled reel starts playing on its own, so the window follows it with a pause button. Setting
    // the params again once the window opens matters too: the Android 17 PiP menu can grey the skip
    // buttons the wrong way round from the params the window entered with, until they are set again.
    LaunchedEffect(pagerState.settledPage, navigation, isInPip) {
        PictureInPictureHelper.setShortsNavigation(navigation)
        if (inPip) restateWindow(isPlaying = true)
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
            runCatching { activity.unregisterReceiver(receiver) }
                .onFailure { Log.w(TAG, "Failed to unregister the Shorts PiP receiver", it) }
        }
    }
}
