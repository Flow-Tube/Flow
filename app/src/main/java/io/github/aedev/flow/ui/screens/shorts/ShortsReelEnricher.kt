package io.github.aedev.flow.ui.screens.shorts

import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.model.ShortLinkedVideo
import io.github.aedev.flow.data.model.ShortVideo
import io.github.aedev.flow.data.model.toVideo
import io.github.aedev.flow.data.repository.YouTubeRepository
import io.github.aedev.flow.data.shorts.ShortDetails
import io.github.aedev.flow.data.shorts.ShortsFeedRepository
import io.github.aedev.flow.data.shorts.ShortsMetadataRepository
import io.github.aedev.flow.data.shorts.queue.ShortsQueueChange
import io.github.aedev.flow.data.shorts.queue.ShortsQueueController
import io.github.aedev.flow.innertube.pages.reel.ReelOverlay
import io.github.aedev.flow.utils.OriginalTextPolicy
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Fills in a reel the queue only knows by id or by a feed card: its `/player` details, the overlay's
 * counts and sound, and the watch page the description sheet reads. Every result goes back through
 * the queue, which [publishQueue] mirrors to the screen.
 */
internal class ShortsReelEnricher(
    private val scope: CoroutineScope,
    private val repository: YouTubeRepository,
    private val feed: ShortsFeedRepository,
    private val metadata: ShortsMetadataRepository,
    private val playerPreferences: PlayerPreferences,
    private val shorts: () -> List<ShortVideo>,
    private val queue: () -> ShortsQueueController?,
    private val publishQueue: () -> Unit,
) {
    /** Counts, avatar, timestamp and sound for the reel on screen — one overlay request, cached. */
    fun loadDetails(videoId: String) {
        scope.launch(PerformanceDispatcher.networkIO) {
            val overlay = runCatching { metadata.overlayFor(videoId) }.getOrNull() ?: return@launch
            val preferOriginal = playerPreferences.originalTitles.first()
            enrich(videoId) { it.withOverlay(overlay, preferOriginal) }
        }
    }

    /** The description sheet needs the watch page: description, absolute date, exact counts. */
    fun loadDescription(videoId: String) {
        scope.launch(PerformanceDispatcher.networkIO) {
            val existing = shorts().firstOrNull { it.id == videoId } ?: return@launch
            if (existing.description.isNotBlank()) return@launch
            val enriched = runCatching { repository.enrichFromWatchMetadata(existing.toVideo()) }.getOrNull() ?: return@launch
            enrich(videoId) { short ->
                short.copy(
                    description = enriched.description.ifBlank { short.description },
                    uploadDate = enriched.uploadDate.ifBlank { short.uploadDate },
                    likeCount = if (enriched.likeCount > 0) enriched.likeCount else short.likeCount,
                    viewCount = if (enriched.viewCount > 0) enriched.viewCount else short.viewCount,
                    channelThumbnailUrl = enriched.channelThumbnailUrl.ifBlank { short.channelThumbnailUrl },
                )
            }
        }
    }

    /**
     * A sequence reel is an id until `/player` names it, so blocked channels and topics are
     * judged here as well as at page assembly; a reel that fails leaves the queue instead.
     */
    suspend fun applyDetails(
        videoId: String,
        details: ShortDetails,
    ) {
        val dropped =
            feed.isBlocked(details.channelId, details.title, details.channelName) &&
                (queue()?.dropFiltered(videoId) ?: ShortsQueueChange.None) != ShortsQueueChange.None
        if (dropped) {
            publishQueue()
            return
        }
        val preferOriginal = playerPreferences.originalTitles.first()
        enrich(videoId) { short ->
            short.copy(
                title = OriginalTextPolicy.streamTitle(details.title, short.title, preferOriginal).orEmpty(),
                channelName = details.channelName.ifBlank { short.channelName },
                channelId = details.channelId.ifBlank { short.channelId },
                viewCount = details.viewCount ?: short.viewCount,
                durationMs = details.durationMs ?: short.durationMs,
            )
        }
    }

    private fun enrich(
        videoId: String,
        transform: (ShortVideo) -> ShortVideo,
    ) {
        val existing = shorts().firstOrNull { it.id == videoId } ?: return
        val enriched = transform(existing)
        if (enriched == existing) return
        if (queue()?.applyEnrichment(listOf(enriched)) != ShortsQueueChange.None) publishQueue()
    }
}

/** The overlay is translated like any list, `/player` is not, and the switch picks one (#1230). */
internal fun ShortVideo.withOverlay(
    overlay: ReelOverlay,
    preferOriginal: Boolean,
): ShortVideo =
    copy(
        title = OriginalTextPolicy.streamTitle(title, overlay.title, preferOriginal).orEmpty(),
        channelName = channelName.ifBlank { overlay.channelName.orEmpty() },
        channelId = channelId.ifBlank { overlay.channelId.orEmpty() },
        channelThumbnailUrl = overlay.channelAvatarUrl ?: channelThumbnailUrl,
        likeCount = overlay.likeCount ?: likeCount,
        commentCount = overlay.commentCount ?: commentCount,
        uploadDate = uploadDate.ifBlank { overlay.relativeTimestamp.orEmpty() },
        soundTitle = overlay.soundTitle ?: soundTitle,
        soundThumbnailUrl = overlay.soundThumbnailUrl ?: soundThumbnailUrl,
        linkedVideo =
            overlay.companionVideoId?.let { ShortLinkedVideo(it, overlay.companionVideoTitle) } ?: linkedVideo,
    )
