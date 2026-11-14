/*
 * Copyright (C) 2025-2026 Flow | A-EDev
 *
 * This file is part of Flow (https://github.com/Flow-Tube/Flow).
 */

package io.github.aedev.flow.data.recommendation

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.contentfilter.aislist.AiListMatcher
import io.github.aedev.flow.data.contentfilter.aislist.AiListSets
import io.github.aedev.flow.data.model.Video
import org.junit.Test

class FeedExclusionsTest {
    private val day = 86_400_000L

    @Test
    fun `a not interested mark never expires by age`() {
        val marked = suppressVideo(mapOf("old" to 0L), "new", now = 400 * day)

        assertThat(marked.keys).containsExactly("old", "new")
    }

    @Test
    fun `past the cap the oldest marks are dropped first`() {
        val existing = mapOf("a" to 1L, "b" to 2L, "c" to 3L)

        val marked = suppressVideo(existing, "d", now = 4L, max = 3)

        assertThat(marked.keys).containsExactly("b", "c", "d")
    }

    @Test
    fun `marking a video again refreshes it instead of adding a duplicate`() {
        val marked = suppressVideo(mapOf("a" to 1L, "b" to 2L), "a", now = 5L, max = 2)

        assertThat(marked).containsExactly("a", 5L, "b", 2L)
    }

    @Test
    fun `a reset keeps what the viewer hid and forgets what was learned`() {
        val brain =
            UserBrain(
                globalVector = ContentVector(topics = mapOf("guitar" to 0.6)),
                preferredTopics = setOf("music"),
                totalInteractions = 120,
                blockedChannels = setOf("UCblocked"),
                blockedTopics = setOf("phonk"),
                suppressedVideoIds = mapOf("v1" to 10L),
                suppressedChannels = mapOf("UCsuppressed" to 20L),
            )

        val reset = brain.keepingHiddenContent()

        assertThat(reset.blockedChannels).containsExactly("UCblocked")
        assertThat(reset.blockedTopics).containsExactly("phonk")
        assertThat(reset.suppressedVideoIds).containsExactly("v1", 10L)
        assertThat(reset.suppressedChannels).containsExactly("UCsuppressed", 20L)
        assertThat(reset.globalVector.topics).isEmpty()
        assertThat(reset.preferredTopics).isEmpty()
        assertThat(reset.totalInteractions).isEqualTo(0)
    }

    @Test
    fun `hiding channels adds to the exclusions without dropping any`() {
        val base =
            FeedExclusions(
                suppressedVideoIds = setOf("v1"),
                blockedChannelIds = setOf("UCblocked"),
                blockedText = { title, _ -> title == "phonk" },
            )

        val widened = base.hidingChannels(setOf("UCsub"))

        assertThat(widened.hidesFromRecommendations(video("v2", "UCsub"))).isTrue()
        assertThat(widened.hidesFromRecommendations(video("v1", "UCother"))).isTrue()
        assertThat(widened.hidesFromRecommendations(video("v3", "UCblocked"))).isTrue()
        assertThat(widened.hidesFromRecommendations(video("v4", "UCother", title = "phonk"))).isTrue()
        assertThat(widened.hidesFromRecommendations(video("v5", "UCother"))).isFalse()
        assertThat(base.hidingChannels(emptySet())).isSameInstanceAs(base)
    }

    @Test
    fun `an AI-listed channel leaves recommendations but not Subscriptions`() {
        val exclusions = FeedExclusions(ai = aiList(blockedId = "UCslopslopslopslopslop00"))
        val listed = video("v1", "UCslopslopslopslopslop00")

        assertThat(exclusions.hidesFromRecommendations(listed)).isTrue()
        assertThat(exclusions.hidesAsAi(listed)).isTrue()
        assertThat(exclusions.hides(listed)).isFalse()
        assertThat(exclusions.isEmpty).isFalse()
        assertThat(exclusions.hidingChannels(setOf("UCsub")).hidesAsAi(listed)).isTrue()
    }

    private fun aiList(blockedId: String) =
        AiListMatcher(
            blockEnabled = true,
            warnEnabled = false,
            block = AiListSets(handles = emptySet(), ids = setOf(blockedId)),
            warn = AiListSets.EMPTY,
            exemptChannelIds = emptySet(),
            exemptHandles = emptySet(),
            index = null,
        )

    private fun video(
        id: String,
        channelId: String,
        title: String = "title-$id",
    ) = Video(
        id = id,
        title = title,
        channelName = channelId,
        channelId = channelId,
        thumbnailUrl = "",
        duration = 600,
        viewCount = 1,
        uploadDate = "",
    )
}
