package io.github.aedev.flow.data.recommendation

import io.github.aedev.flow.data.contentfilter.aislist.AiListMatcher
import io.github.aedev.flow.data.model.Video

/**
 * What the viewer asked not to see, read from the brain in one snapshot for the surfaces that never
 * pass through [FlowNeuroEngine.rank]: Subscriptions, the subscription Shorts queue, search, the
 * player's related list and autoplay, and the Home feed restored from cache.
 *
 * Two strengths, because not every surface should hide the same things (owner decision D5):
 * [hides] is what the viewer said outright, a video marked not interested or a blocked channel, and
 * applies everywhere, Subscriptions included. [hidesFromRecommendations] adds what the engine
 * inferred, the channels a not-interested mark suppresses for a while and blocked topics, and applies
 * only where the app chose the video rather than the viewer.
 *
 * The AI channel list ([ai]) joins [hidesFromRecommendations], so it never reaches Subscriptions.
 * Search, which uses [hides], asks [hidesAsAi] on its own.
 */
class FeedExclusions(
    val suppressedVideoIds: Set<String> = emptySet(),
    val blockedChannelIds: Set<String> = emptySet(),
    val suppressedChannelIds: Set<String> = emptySet(),
    private val blockedText: (title: String, channelName: String) -> Boolean = { _, _ -> false },
    val ai: AiListMatcher = AiListMatcher.NONE,
) {
    val isEmpty: Boolean
        get() = suppressedVideoIds.isEmpty() && blockedChannelIds.isEmpty() && suppressedChannelIds.isEmpty() && !ai.isActive

    fun hides(video: Video): Boolean = video.id in suppressedVideoIds || hidesChannel(video.channelId)

    fun hidesChannel(channelId: String): Boolean = channelId.isNotBlank() && channelId in blockedChannelIds

    fun hidesAsAi(video: Video): Boolean = ai.blocks(video)

    fun hidesFromRecommendations(video: Video): Boolean =
        hides(video) ||
            (video.channelId.isNotBlank() && video.channelId in suppressedChannelIds) ||
            blockedText(video.title, video.channelName) ||
            ai.blocks(video)

    /** The same exclusions, also hiding every video from [channelIds]. */
    fun hidingChannels(channelIds: Set<String>): FeedExclusions =
        if (channelIds.isEmpty()) {
            this
        } else {
            FeedExclusions(suppressedVideoIds, blockedChannelIds + channelIds, suppressedChannelIds, blockedText, ai)
        }

    companion object {
        val NONE = FeedExclusions()
    }
}

/** Records a "not interested" mark. Marks never expire; past [max] the oldest are dropped. */
internal fun suppressVideo(
    suppressed: Map<String, Long>,
    videoId: String,
    now: Long,
    max: Int = FlowNeuroEngine.MAX_SUPPRESSED_VIDEOS,
): Map<String, Long> {
    val updated = suppressed + (videoId to now)
    if (updated.size <= max) return updated
    return updated.entries
        .sortedByDescending { it.value }
        .take(max)
        .associate { it.key to it.value }
}

/** A fresh brain that still carries every block and "not interested" mark of this one. */
internal fun UserBrain.keepingHiddenContent(): UserBrain =
    UserBrain(
        blockedChannels = blockedChannels,
        blockedTopics = blockedTopics,
        suppressedVideoIds = suppressedVideoIds,
        suppressedChannels = suppressedChannels,
    )
