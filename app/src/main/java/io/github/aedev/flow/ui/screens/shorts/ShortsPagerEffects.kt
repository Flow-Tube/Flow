package io.github.aedev.flow.ui.screens.shorts

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.model.ShortVideo
import io.github.aedev.flow.player.shorts.ShortsPlayerPool
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "ShortsPagerEffects"

/** A reel the user swiped past in under a second was never really shown. */
private const val SHOWN_DWELL_MS = 1_000L

/**
 * The page whose reel plays: the settled page while a finger holds the pager, and the page it is
 * heading to from the moment the finger lifts. Waiting for the snap to finish left the old reel
 * playing with no picture and started the new one a snap late.
 */
@Composable
internal fun rememberShortsPlayPage(pagerState: PagerState): State<Int> {
    val isDragged = pagerState.interactionSource.collectIsDraggedAsState()
    return remember(pagerState) {
        derivedStateOf { if (pagerState.isScrollInProgress && !isDragged.value) pagerState.targetPage else pagerState.settledPage }
    }
}

/**
 * Prepares the playing reel and its neighbours whenever [playPage] changes, and re-resolves them when
 * the quality preference changes underneath a running reel.
 */
@Composable
internal fun ShortsPagerPlaybackEffects(
    playPage: Int,
    shorts: List<ShortVideo>,
    targetHeight: Int?,
    isWifi: Boolean,
    playerPreferences: PlayerPreferences,
    viewModel: ShortsViewModel,
) {
    val context = LocalContext.current
    val playerPool = remember { ShortsPlayerPool.getInstance() }
    val isLowRamDevice =
        remember(context) {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice ==
                true
        }
    val playShortId = shorts.getOrNull(playPage)?.id
    val preparedShortId = remember { mutableStateOf<String?>(null) }

    LaunchedEffect(playPage, playShortId, targetHeight) {
        val height = targetHeight ?: return@LaunchedEffect
        val current = shorts.getOrNull(playPage) ?: return@LaunchedEffect
        preparedShortId.value = null
        playerPool.initialize(context)
        playerPool.setCurrentVideo(current)
        val preferredLang = playerPreferences.preferredAudioLanguage.first()

        playerPool.activatePlayer(playPage, current.id)
        prepareReel(playerPool, viewModel, playPage, current, height, preferredLang, shouldPlay = true)
        viewModel.loadShortDetails(current.id)
        launch {
            delay(SHOWN_DWELL_MS)
            viewModel.onReelShown(current.id)
        }

        playerPool.releaseUnusedPlayers(playPage)
        preparedShortId.value = current.id
    }

    // Keyed on the neighbours' ids as well as the page: enrichment can reorder the reels after the
    // current one, and the pool would otherwise keep buffering whichever short used to sit there.
    // Kept apart from the effect above so a neighbour change never re-activates a paused reel.
    val previousShortId = shorts.getOrNull(playPage - 1)?.id
    val nextShortId = shorts.getOrNull(playPage + 1)?.id
    val preloadShortId = shorts.getOrNull(playPage + 2)?.id
    // A trim under memory pressure dropped the neighbours; preparing them again needs a fresh run.
    val trimGeneration by playerPool.trimGeneration.collectAsState()
    LaunchedEffect(playPage, playShortId, previousShortId, nextShortId, preloadShortId, targetHeight, trimGeneration) {
        val height = targetHeight ?: return@LaunchedEffect
        val currentId = playShortId ?: return@LaunchedEffect
        val settled = playPage
        // Each resolve mints a BotGuard PoToken, and those serialise on one process-wide WebView, so
        // the neighbours wait for the short the user is looking at rather than queue ahead of it.
        snapshotFlow { preparedShortId.value }.first { it == currentId }
        val preferredLang = playerPreferences.preferredAudioLanguage.first()

        shorts.getOrNull(settled + 1)?.let { next ->
            launch { prepareReel(playerPool, viewModel, settled + 1, next, height, preferredLang, shouldPlay = false) }
        }
        // On a low-RAM device only the reel ahead is kept ready, so at most two decoders are live.
        shorts.getOrNull(settled - 1)?.takeUnless { isLowRamDevice }?.let { previous ->
            launch { prepareReel(playerPool, viewModel, settled - 1, previous, height, preferredLang, shouldPlay = false) }
        }
        // Two ahead: resolved only, not handed to a player. Last so it never competes with the visible short.
        shorts.getOrNull(settled + 2)?.let { preload ->
            launch { runCatching { viewModel.getPlaybackStreams(preload.id, height, preferredLang) } }
        }
    }

    val previousTargetHeight = remember { mutableStateOf<Int?>(null) }
    val previousWifi = remember { mutableStateOf(isWifi) }
    val latestWifi by rememberUpdatedState(isWifi)
    val latestPlayPage by rememberUpdatedState(playPage)
    LaunchedEffect(targetHeight) {
        val height = targetHeight ?: return@LaunchedEffect
        val previous = previousTargetHeight.value
        previousTargetHeight.value = height
        val networkChanged = previousWifi.value != latestWifi
        previousWifi.value = latestWifi
        // The first non-null value is the preference loading, not the user changing it. The settle
        // effect above already prepares at that height.
        if (previous == null || height == previous) return@LaunchedEffect

        val settled = latestPlayPage
        val preferredLang = playerPreferences.preferredAudioLanguage.first()

        // A network change must not restart the reel being watched; the new quality starts with the next one.
        if (!networkChanged) {
            shorts.getOrNull(settled)?.let { current -> reloadReel(playerPool, viewModel, settled, current, height, preferredLang) }
        }
        shorts.getOrNull(settled + 1)?.let { next ->
            launch { reloadReel(playerPool, viewModel, settled + 1, next, height, preferredLang) }
        }
        shorts.getOrNull(settled - 1)?.let { previousShort ->
            launch { reloadReel(playerPool, viewModel, settled - 1, previousShort, height, preferredLang) }
        }
    }
}

private suspend fun prepareReel(
    playerPool: ShortsPlayerPool,
    viewModel: ShortsViewModel,
    index: Int,
    short: ShortVideo,
    targetHeight: Int,
    preferredLang: String,
    shouldPlay: Boolean,
) {
    try {
        val streams = viewModel.getPlaybackStreams(short.id, targetHeight, preferredLang)
        if (streams == null) {
            Log.w(TAG, "No stream URL resolved for ${short.id}")
            return
        }
        playerPool.prepare(
            index = index,
            videoId = short.id,
            videoUrl = streams.videoUrl,
            audioUrl = streams.audioUrl,
            shouldPlay = shouldPlay,
            videoDashManifest = streams.videoDashManifest,
            audioDashManifest = streams.audioDashManifest,
            loudnessGainDb = streams.loudnessGainDb,
        )
    } catch (e: Exception) {
        Log.e(TAG, "Failed to prepare player for ${short.id}", e)
    }
}

private suspend fun reloadReel(
    playerPool: ShortsPlayerPool,
    viewModel: ShortsViewModel,
    index: Int,
    short: ShortVideo,
    targetHeight: Int,
    preferredLang: String,
) {
    try {
        val streams = viewModel.getPlaybackStreams(short.id, targetHeight, preferredLang) ?: return
        playerPool.reloadWithVideoUrl(index, short.id, streams.videoUrl, streams.videoDashManifest)
    } catch (e: Exception) {
        Log.e(TAG, "Quality change: failed to reload ${short.id}", e)
    }
}
