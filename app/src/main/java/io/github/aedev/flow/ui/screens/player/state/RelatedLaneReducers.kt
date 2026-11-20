package io.github.aedev.flow.ui.screens.player.state

import io.github.aedev.flow.data.model.Video

/** Whether the lane still waits on [continuation] for [videoId], so a late page can land. */
internal fun VideoPlayerUiState.awaitsRelatedPage(
    videoId: String,
    continuation: String,
): Boolean = cachedVideo?.id == videoId && relatedContinuation == continuation

/**
 * A further related page behind the lane. A video an earlier page already listed keeps its place,
 * and [continuation] replaces the one that was spent.
 */
internal fun VideoPlayerUiState.appendRelatedPage(
    videos: List<Video>,
    continuation: String?,
): VideoPlayerUiState =
    copy(
        relatedVideos = (relatedVideos + videos).distinctBy { it.id },
        relatedContinuation = continuation,
        relatedAppend = RelatedAppend.IDLE,
    )
