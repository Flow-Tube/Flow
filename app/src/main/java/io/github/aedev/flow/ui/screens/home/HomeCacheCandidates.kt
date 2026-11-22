package io.github.aedev.flow.ui.screens.home

import io.github.aedev.flow.data.local.CachedHomeVideo
import io.github.aedev.flow.data.model.Video

internal fun cacheCandidates(
    source: FeedSource,
    videos: List<Video>,
    excludedIds: Set<String> = emptySet(),
): List<CachedHomeVideo> =
    videos
        .asSequence()
        .filterNot { it.id in excludedIds }
        .distinctBy { it.id }
        .map { CachedHomeVideo(it, source.name) }
        .toList()

internal fun cacheRelatedCandidates(
    videos: List<Video>,
    metadata: Map<String, GraphCandidate>,
    excludedIds: Set<String> = emptySet(),
): List<CachedHomeVideo> =
    videos
        .asSequence()
        .filterNot { it.id in excludedIds }
        .distinctBy { it.id }
        .map { video ->
            CachedHomeVideo(
                video = video,
                source = FeedSource.RELATED.name,
                relatedSeedId = metadata[video.id]?.seedId,
            )
        }.toList()
