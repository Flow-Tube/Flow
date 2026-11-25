package io.github.aedev.flow.ui.screens.player.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.aedev.flow.ui.screens.player.VideoPlayerViewModel
import io.github.aedev.flow.ui.screens.player.state.UpcomingPollPolicy
import kotlinx.coroutines.delay

/**
 * Watches a premiere or scheduled stream until it starts: one light `/player` call at the interval
 * YouTube asks for, and the full load only when [UpcomingPollPolicy] says so. A premiere does not
 * flip at its announced time, and a scheduled stream can start early, late or without a time at all,
 * so the poll does not wait for the countdown.
 *
 * The poll is bound to STARTED: nothing consumes the answer while the player is not on screen, and a
 * backgrounded premiere used to keep a network wakeup running. Each cycle starts with a wait, so the
 * load that entered the countdown is never asked twice in a row.
 */
@Composable
internal fun UpcomingVideoRefreshEffect(
    videoId: String,
    isUpcoming: Boolean,
    viewModel: VideoPlayerViewModel,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(videoId, isUpcoming, lifecycleOwner) {
        if (!isUpcoming) return@LaunchedEffect
        var broadcastId: String? = null
        var delayMs = UpcomingPollPolicy.DEFAULT_DELAY_MS
        var polling = true
        var loads = 0
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (polling && loads < UpcomingPollPolicy.MAX_FULL_LOADS && viewModel.uiState.value.isUpcoming) {
                delay(delayMs)
                when (val step = UpcomingPollPolicy.next(broadcastId, viewModel.pollUpcoming(videoId))) {
                    is UpcomingPollPolicy.Step.Load -> {
                        broadcastId = step.broadcastId
                        polling = step.keepPolling
                        loads++
                        viewModel.loadVideoInfo(videoId, forceRefresh = true)
                    }

                    is UpcomingPollPolicy.Step.Wait -> {
                        broadcastId = step.broadcastId
                        delayMs = step.delayMs
                    }
                }
            }
        }
    }
}
