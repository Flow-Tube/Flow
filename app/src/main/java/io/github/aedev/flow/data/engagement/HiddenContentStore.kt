package io.github.aedev.flow.data.engagement

import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.recommendation.FeedExclusions
import io.github.aedev.flow.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/** Videos marked "Not interested" and channels blocked since the process started. */
data class HiddenContent(
    val videoIds: Set<String> = emptySet(),
    val channelIds: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = videoIds.isEmpty() && channelIds.isEmpty()

    fun hidesVideo(videoId: String): Boolean = videoId in videoIds

    fun hides(video: Video): Boolean = hides(video.id, video.channelId)

    fun hides(
        videoId: String,
        channelId: String,
    ): Boolean = hidesVideo(videoId) || (channelId.isNotBlank() && channelId in channelIds)

    /** The same marks as [FeedExclusions], for code that already filters by those. */
    fun asExclusions(): FeedExclusions = FeedExclusions(suppressedVideoIds = videoIds, blockedChannelIds = channelIds)

    fun after(event: FeedInvalidationBus.Event): HiddenContent =
        when (event) {
            is FeedInvalidationBus.Event.NotInterested -> {
                copy(videoIds = videoIds + event.videoId)
            }

            is FeedInvalidationBus.Event.ChannelBlocked -> {
                copy(
                    videoIds = if (event.videoId.isBlank()) videoIds else videoIds + event.videoId,
                    channelIds = if (event.channelId.isBlank()) channelIds else channelIds + event.channelId,
                )
            }

            is FeedInvalidationBus.Event.MarkedWatched, is FeedInvalidationBus.Event.ChannelUnsubscribed -> {
                this
            }
        }
}

/**
 * Every hide the viewer makes, gathered from [FeedInvalidationBus] for the paged screens (Search,
 * Channel, History), which load their exclusions once and would otherwise keep a hidden video until
 * they reload. Collected eagerly on purpose: a hide made on the watch page must reach a search
 * results screen that sits in the back stack and is not being collected at that moment.
 */
@Singleton
class HiddenContentStore
    @Inject
    constructor(
        @ApplicationScope scope: CoroutineScope,
    ) {
        val hidden: StateFlow<HiddenContent> =
            FeedInvalidationBus.events
                .scan(HiddenContent(), HiddenContent::after)
                .stateIn(scope, SharingStarted.Eagerly, HiddenContent())
    }
