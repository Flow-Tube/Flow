package io.github.aedev.flow.ui.screens.home

import android.util.Log
import io.github.aedev.flow.data.feed.FeedPrefetchQueue
import io.github.aedev.flow.data.feed.FeedPrefetchRequest
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class HomeLoadMore(
    private val scope: CoroutineScope,
    private val session: HomeFeedSession,
    private val homePrefetchQueue: FeedPrefetchQueue,
    private val pages: HomeLoadMorePages,
    private val wave2: HomeWave2Discovery,
) {
    private val uiState = session.uiState
    private val homePrefetchWorkerLock = Any()
    private var homePrefetchJob: Job? = null

    fun onVisible() {
        val state = uiState.value
        startHomePrefetch(
            homePrefetchQueue.onVisible(
                currentItemCount = state.videos.size,
                feedReady = state.isReadyForPrefetch(),
            ),
        )
    }

    fun onHidden() {
        homePrefetchQueue.onHidden()
        synchronized(homePrefetchWorkerLock) {
            homePrefetchJob?.cancel()
        }
    }

    fun onViewportChanged(
        state: HomeUiState,
        lastVisibleVideoIndex: Int,
    ) {
        if (!state.isReadyForPrefetch()) return
        startHomePrefetch(
            homePrefetchQueue.onViewportChanged(
                currentItemCount = state.videos.size,
                lastVisibleItemIndex = lastVisibleVideoIndex,
            ),
        )
    }

    private fun HomeUiState.isReadyForPrefetch(): Boolean = videos.isNotEmpty() && !isLoading && isFlowFeed && hasMorePages

    private fun startHomePrefetch(request: FeedPrefetchRequest?) {
        request ?: return
        val worker =
            synchronized(homePrefetchWorkerLock) {
                if (homePrefetchJob?.isCompleted == false) return
                scope
                    .launch(
                        context = PerformanceDispatcher.networkIO,
                        start = CoroutineStart.LAZY,
                    ) {
                        drainHomePrefetchQueue(request.generation)
                    }.also { homePrefetchJob = it }
            }
        worker.start()
    }

    private suspend fun drainHomePrefetchQueue(generation: Int) {
        var pagesLoaded = 0
        var attempt = 0
        var held: HeldPage? = null
        var allowRestart = true
        try {
            wave2.awaitRunning()
            while (pagesLoaded < HOME_PREFETCH_MAX_PAGES_PER_RUN) {
                val state = uiState.value
                val request = homePrefetchQueue.currentRequest(state.videos.size) ?: break
                if (request.generation != generation || !state.hasMorePages) break

                uiState.update { it.copy(isLoadingMore = true, loadMoreFailed = false) }
                attempt++
                val result = pages.loadNextPrefetchPage(generation, held, lastAttempt = attempt >= HOME_PREFETCH_EMPTY_PAGE_RETRIES)
                held = result.held
                if (!homePrefetchQueue.isCurrent(generation)) break

                // A page that appended nothing leaves the feed at the same length, so the
                // viewport index cannot change and nothing would re-arm this queue. Retry a
                // few times — each attempt rotates queries and seeds — before giving up.
                when (val step = nextLoadMoreStep(result.outcome, attempt)) {
                    LoadMoreStep.DONE -> {
                        pagesLoaded++
                    }

                    LoadMoreStep.RETRY -> {
                        if (result.outcome == LoadMoreOutcome.EXHAUSTED) delay(HOME_PREFETCH_EMPTY_PAGE_BACKOFF_MS * attempt)
                    }

                    LoadMoreStep.END_FEED, LoadMoreStep.STOP_FAILED -> {
                        uiState.update {
                            if (step == LoadMoreStep.END_FEED) it.copy(hasMorePages = false) else it.copy(loadMoreFailed = true)
                        }
                        Log.w(HOME_LOG_TAG, "Load-more stopped: $step after $attempt attempts at ${state.videos.size} cards")
                        allowRestart = false
                        break
                    }
                }
            }
            if (pagesLoaded >= HOME_PREFETCH_MAX_PAGES_PER_RUN) {
                allowRestart = false
            }
        } catch (cancellation: CancellationException) {
            allowRestart = false
            throw cancellation
        } finally {
            val workerJob = currentCoroutineContext()[Job]
            val ownsLoadingState =
                synchronized(homePrefetchWorkerLock) {
                    if (homePrefetchJob === workerJob) {
                        homePrefetchJob = null
                        true
                    } else {
                        false
                    }
                }
            if (ownsLoadingState) {
                uiState.update { it.copy(isLoadingMore = false) }
                if (allowRestart) {
                    startHomePrefetch(homePrefetchQueue.currentRequest(uiState.value.videos.size))
                }
            }
        }
    }

    fun retryLoadMore() {
        uiState.update { it.copy(loadMoreFailed = false) }
        startHomePrefetch(homePrefetchQueue.currentRequest(uiState.value.videos.size))
    }

    fun resetHomePrefetch() {
        homePrefetchQueue.reset()
        synchronized(homePrefetchWorkerLock) {
            homePrefetchJob?.cancel()
        }
        uiState.update { it.copy(isLoadingMore = false, loadMoreFailed = false) }
    }
}
