package io.github.aedev.flow.ui.screens.shorts

import io.github.aedev.flow.data.engagement.VideoEngagementUseCase
import io.github.aedev.flow.data.local.PlaylistRepository
import io.github.aedev.flow.data.model.ShortVideo
import io.github.aedev.flow.data.model.toVideo
import io.github.aedev.flow.data.recommendation.FlowNeuroEngine
import io.github.aedev.flow.data.recommendation.InteractionType
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The like, subscribe and save state of the reels on screen, and the toggles that change it. */
internal class ShortsEngagementState(
    private val scope: CoroutineScope,
    private val engagement: VideoEngagementUseCase,
    private val playlistRepository: PlaylistRepository,
) {
    private val savedShortIds = MutableStateFlow<Set<String>>(emptySet())

    fun collectSavedShorts() {
        scope.launch(PerformanceDispatcher.diskIO) {
            playlistRepository.getSavedShortsFlow().collect { savedVideos ->
                savedShortIds.value = savedVideos.map { it.id }.toSet()
            }
        }
    }

    /**
     * `WhileSubscribed` matters here: the page calls this from `remember(video.id)`, so a
     * hand-rolled `launch { collect { } }` left one permanent Room observer per short scrolled
     * past — a few hundred of them after a long session, every one waking on every write.
     */
    fun isLiked(videoId: String): StateFlow<Boolean> =
        engagement
            .likeState(videoId)
            .map { it == "LIKED" }
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = false,
            )

    fun isSubscribed(channelId: String): StateFlow<Boolean> =
        engagement
            .subscriptionState(channelId)
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = false,
            )

    fun isSaved(videoId: String): StateFlow<Boolean> =
        savedShortIds
            .map { it.contains(videoId) }
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = savedShortIds.value.contains(videoId),
            )

    /**
     * Liking a short carries no learning signal: the deliberate "more like this" action is
     * what feeds the engine, and firing on the like too would double-count it.
     */
    suspend fun toggleLike(short: ShortVideo) {
        val video = short.toVideo()
        if (engagement.likeState(video.id).first() == "LIKED") {
            engagement.removeLike(video.id)
        } else {
            engagement.like(video)
        }
    }

    suspend fun toggleSubscription(
        channelId: String,
        channelName: String,
        channelThumbnail: String,
    ) = engagement.toggleSubscription(channelId, channelName, channelThumbnail)

    fun toggleSave(short: ShortVideo) {
        scope.launch(PerformanceDispatcher.diskIO) {
            val video = short.toVideo()
            if (playlistRepository.isInSavedShorts(video.id)) {
                playlistRepository.removeFromSavedShorts(video.id)
            } else {
                playlistRepository.addToSavedShorts(video)
                runCatching {
                    FlowNeuroEngine.onVideoInteraction(video.copy(isShort = true), InteractionType.SAVED)
                }
            }
        }
    }
}
