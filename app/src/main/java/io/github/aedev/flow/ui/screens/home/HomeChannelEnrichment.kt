package io.github.aedev.flow.ui.screens.home

import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.repository.needsChannelMetadata

/** Cards sharing a channel share one lookup; a card with no channel id is asked about alone. */
internal fun Video.channelKey(): String = channelId.ifBlank { "video:$id" }

/**
 * One card per channel that still lacks its name or avatar and has not been asked about yet, for a
 * single lookup round when a page lands. [asked] remembers every key handed out, so a page landing
 * again (or the round's own write) asks for nothing twice.
 */
internal fun channelsToEnrich(
    videos: List<Video>,
    asked: MutableSet<String>,
): List<Video> =
    videos
        .asSequence()
        .filter { it.needsChannelMetadata() }
        .distinctBy { it.channelKey() }
        .filter { asked.add(it.channelKey()) }
        .toList()

/** What each lookup learned, by the channel key of the card it was asked for. */
internal fun enrichedByChannel(
    asked: List<Video>,
    answered: List<Video>,
): Map<String, Video> =
    asked
        .zip(answered)
        .filter { (before, after) -> before != after }
        .associate { (before, after) -> before.channelKey() to after }

/** [this] with every card that still lacks channel details filled from its channel's lookup. */
internal fun List<Video>.withEnrichedChannels(enriched: Map<String, Video>): List<Video> =
    map { video ->
        if (!video.needsChannelMetadata()) return@map video
        enriched[video.channelKey()]?.let(video::withChannelMetadataFrom) ?: video
    }
