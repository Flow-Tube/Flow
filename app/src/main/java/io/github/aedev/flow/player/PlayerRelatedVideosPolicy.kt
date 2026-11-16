package io.github.aedev.flow.player

import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.recommendation.FeedExclusions

object PlayerRelatedVideosPolicy {
    fun select(
        videoId: String,
        primary: List<Video>,
        fallback: List<Video>,
        current: List<Video>,
        shortsEnabled: Boolean = true,
        exclusions: FeedExclusions = FeedExclusions.NONE,
    ): List<Video> =
        sequenceOf(primary, fallback, current)
            .map { candidates -> sanitize(videoId, candidates, shortsEnabled, exclusions) }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()

    /**
     * [exclusions] drops what the viewer hid, a blocked creator or a video marked not interested,
     * the way the home feed already drops it. It matters beyond the cards on screen: this same list
     * seeds autoplay and the queue, so leaving a hidden video in it would keep playing it (#1031).
     */
    fun sanitize(
        videoId: String,
        candidates: List<Video>,
        shortsEnabled: Boolean = true,
        exclusions: FeedExclusions = FeedExclusions.NONE,
    ): List<Video> =
        candidates
            .filter { it.id.isNotBlank() && it.id != videoId }
            .filter { shortsEnabled || !it.isShort }
            .filterNot { exclusions.hidesFromRecommendations(it) }
            .distinctBy { it.id }

    /** [sanitize] against a live [source]; a null source hides nothing. */
    suspend fun sanitizeHidden(
        videoId: String,
        candidates: List<Video>,
        source: FeedExclusionsSource?,
        watched: WatchedSuggestionsSource? = null,
    ): List<Video> = withoutWatched(sanitize(videoId, candidates, exclusions = source?.current() ?: FeedExclusions.NONE), watched)

    /**
     * Drops the videos in [watched], unless every candidate is in it: the same list seeds autoplay,
     * which would otherwise stop on a topic the viewer has already watched through (#2054).
     */
    fun withoutWatched(
        videos: List<Video>,
        watched: Set<String>,
    ): List<Video> = if (watched.isEmpty()) videos else videos.filterNot { it.id in watched }.ifEmpty { videos }

    /** [withoutWatched] against a live [source]; a null source hides nothing. */
    suspend fun withoutWatched(
        videos: List<Video>,
        source: WatchedSuggestionsSource?,
    ): List<Video> = if (source == null || videos.isEmpty()) videos else withoutWatched(videos, source.watchedAmong(videos))
}
