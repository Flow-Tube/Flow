/*
 * Copyright (C) 2025-2026 Flow | A-EDev
 *
 * This file is part of Flow (https://github.com/Flow-Tube/Flow).
 */

package io.github.aedev.flow.data.recommendation

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.recommendation.eval.NeuroEval.vec
import io.github.aedev.flow.data.recommendation.eval.NeuroEval.video
import org.junit.Test

class NeuroScoringTest {
    private val now = 1_700_000_000_000L

    // ── Relevance floor ──

    @Test
    fun `relevance floor exempts subscriptions and cold brains`() {
        assertThat(NeuroScoring.calculateRelevanceFloor(0.0, 200, isSubscription = true)).isEqualTo(1.0)
        assertThat(NeuroScoring.calculateRelevanceFloor(0.0, 50, isSubscription = false)).isEqualTo(1.0)
    }

    @Test
    fun `relevance floor penalizes off-topic content on mature brains`() {
        assertThat(NeuroScoring.calculateRelevanceFloor(0.03, 200, false))
            .isEqualTo(NeuroScoring.RELEVANCE_FLOOR_SEVERE_PENALTY)
        assertThat(NeuroScoring.calculateRelevanceFloor(0.08, 200, false))
            .isEqualTo(NeuroScoring.RELEVANCE_FLOOR_MODERATE_PENALTY)
        assertThat(NeuroScoring.calculateRelevanceFloor(0.5, 200, false)).isEqualTo(1.0)
    }

    // ── Already-watched penalty ──

    @Test
    fun `watched penalty is neutral without history and steep on full watches`() {
        val v = video("a", title = "python tutorial", duration = 600)
        assertThat(NeuroScoring.calculateWatchedPenalty(v, null)).isEqualTo(1.0)
        assertThat(NeuroScoring.calculateWatchedPenalty(v, WatchEntry(0.9f, now)))
            .isEqualTo(NeuroScoring.WATCHED_PENALTY_FULL)
    }

    @Test
    fun `music tracks are exempt from the rewatch penalty`() {
        val music = video("m", title = "lofi remix", duration = 200)
        assertThat(NeuroScoring.calculateWatchedPenalty(music, WatchEntry(0.6f, now))).isEqualTo(1.0)
    }

    // ── Channel signal ──

    @Test
    fun `fresh subscribed uploads get a positive channel signal`() {
        val v = video("s", channelId = "ch_s", uploadDate = "2 hours ago")
        val signal = NeuroScoring.calculateChannelSignal(v, UserBrain(), setOf("ch_s"))
        assertThat(signal).isGreaterThan(0.0)
    }

    @Test
    fun `non-subscribed unknown channels are neutral`() {
        val v = video("n", channelId = "ch_n")
        assertThat(NeuroScoring.calculateChannelSignal(v, UserBrain(), emptySet())).isEqualTo(0.0)
    }

    // ── Adaptive jitter ──

    @Test
    fun `jitter is high at cold start and rises with feed staleness`() {
        // Values are FRACTIONS of the median candidate score (rank() multiplies).
        assertThat(NeuroScoring.calculateAdaptiveJitter(10, 0.0)).isEqualTo(NeuroScoring.JITTER_COLD_START)
        assertThat(NeuroScoring.calculateAdaptiveJitter(100, 0.6)).isEqualTo(0.25)
        assertThat(NeuroScoring.calculateAdaptiveJitter(100, 0.0)).isEqualTo(NeuroScoring.JITTER_NORMAL)
        assertThat(NeuroScoring.calculateAdaptiveJitter(100, 0.6))
            .isGreaterThan(NeuroScoring.calculateAdaptiveJitter(100, 0.0))
    }

    // ── Topic affinity key ──

    @Test
    fun `affinity key is order-independent`() {
        assertThat(NeuroScoring.makeAffinityKey("b", "a"))
            .isEqualTo(NeuroScoring.makeAffinityKey("a", "b"))
    }

    // ── Topic probation ──

    @Test
    fun `probation damps unconfirmed mid-weight topics on mature brains`() {
        val brain =
            UserBrain(
                totalInteractions = 200,
                globalVector = ContentVector(topics = mapOf("foo" to 0.1)),
            )
        val penalty =
            NeuroScoring.calculateTopicProbationPenalty(
                vec("foo" to 0.9),
                brain,
                emptySet(),
            )
        assertThat(penalty).isLessThan(1.0)
    }

    @Test
    fun `confirmed topic evidence lifts probation`() {
        val brain =
            UserBrain(
                totalInteractions = 200,
                globalVector = ContentVector(topics = mapOf("foo" to 0.1)),
                topicEvidence = mapOf("foo" to TopicEvidence(explicitSignals = 1)),
            )
        val penalty =
            NeuroScoring.calculateTopicProbationPenalty(
                vec("foo" to 0.9),
                brain,
                emptySet(),
            )
        assertThat(penalty).isEqualTo(1.0)
    }

    @Test
    fun `probation is inactive during cold start`() {
        val brain =
            UserBrain(
                totalInteractions = 10,
                globalVector = ContentVector(topics = mapOf("foo" to 0.1)),
            )
        assertThat(NeuroScoring.calculateTopicProbationPenalty(vec("foo" to 0.9), brain, emptySet()))
            .isEqualTo(1.0)
    }

    // ── Exploration (UCB) ──

    @Test
    fun `exploration bonus is gated by weight and bounded`() {
        val brain = UserBrain(totalInteractions = 200)
        val v = vec("newtopic" to 0.9)
        assertThat(NeuroScoring.explorationBonus(v, brain, exploreWeight = 0.0)).isEqualTo(0.0)
        val bonus = NeuroScoring.explorationBonus(v, brain, exploreWeight = 1.0)
        assertThat(bonus).isGreaterThan(0.0)
        assertThat(bonus).isAtMost(NeuroScoring.EXPLORE_MAX_BONUS)
    }

    @Test
    fun `novel topics get a larger exploration bonus than well-explored ones`() {
        val brain =
            UserBrain(
                totalInteractions = 200,
                topicEvidence = mapOf("known" to TopicEvidence(positiveSignals = 50)),
            )
        val known = NeuroScoring.explorationBonus(vec("known" to 0.9), brain, 1.0)
        val novel = NeuroScoring.explorationBonus(vec("novel" to 0.9), brain, 1.0)
        assertThat(novel).isGreaterThan(known)
    }

    @Test
    fun `exploration is inactive at cold start`() {
        val cold = UserBrain(totalInteractions = 10)
        assertThat(NeuroScoring.explorationBonus(vec("x" to 0.9), cold, 1.0)).isEqualTo(0.0)
    }

    @Test
    fun `repeatedly disliked topics are not explored`() {
        val brain =
            UserBrain(
                totalInteractions = 200,
                topicEvidence = mapOf("hated" to TopicEvidence(negativeSignals = 40)),
            )
        val hated = NeuroScoring.explorationBonus(vec("hated" to 0.9), brain, 1.0)
        val fresh = NeuroScoring.explorationBonus(vec("brandnew" to 0.9), brain, 1.0)
        assertThat(hated).isLessThan(fresh)
    }

    // ── IDF vocabulary cap (I-13 slice) ──

    @Test
    fun `idf cap keeps the most frequent keys and drops the rest`() {
        val freq = HashMap<String, Int>()
        repeat(NeuroScoring.IDF_MAX_KEYS + 500) { freq["w$it"] = it }
        NeuroScoring.capIdfVocabulary(freq)
        assertThat(freq).hasSize(NeuroScoring.IDF_KEEP_KEYS)
        assertThat(freq).containsKey("w${NeuroScoring.IDF_MAX_KEYS + 499}")
        assertThat(freq).doesNotContainKey("w0")
    }

    @Test
    fun `idf cap is a no-op below the limit`() {
        val freq = hashMapOf("a" to 1, "b" to 2)
        NeuroScoring.capIdfVocabulary(freq)
        assertThat(freq).hasSize(2)
    }

    @Test
    fun `lanes keep what shares a topic or a known channel and drop the rest`() {
        val user = NeuroVectorMath.PreparedVector(vec("comedy" to 0.8, "guitar" to 0.2))
        val onTopic = (0 until 6).map { video("comedy$it") to vec("comedy" to 1.0) }
        val subscribed = video("sub", channelId = "subscribed") to vec("prank" to 1.0)
        val watched = video("watched", channelId = "watched") to vec("prank" to 1.0)
        val disliked = video("disliked", channelId = "disliked") to vec("prank" to 1.0)
        val drift = video("drift") to vec("lottery" to 1.0)

        val kept =
            NeuroScoring
                .keepRelevant(
                    onTopic + listOf(subscribed, watched, disliked, drift),
                    user,
                    NeuroScoring.LANE_RELEVANCE_FLOOR,
                    userSubs = setOf("subscribed"),
                    channelScores = mapOf("watched" to 0.6, "disliked" to 0.3),
                ).map { it.first.id }

        assertThat(kept).containsExactlyElementsIn(onTopic.map { it.first.id } + listOf("sub", "watched"))
    }

    @Test
    fun `a thin lane keeps only the videos on the viewer's subjects`() {
        val user = NeuroVectorMath.PreparedVector(vec("comedy" to 0.8, "guitar" to 0.2))
        val onTopic = (0 until 2).map { video("comedy$it") to vec("comedy" to 1.0) }
        val drift = (0 until 6).map { video("drift$it") to vec("lottery" to 1.0) }
        val faint = video("faint") to vec("lottery" to 1.0, "comedy" to 0.01)

        val kept =
            NeuroScoring
                .keepRelevant(onTopic + drift + faint, user, NeuroScoring.LANE_RELEVANCE_FLOOR, emptySet(), emptyMap())
                .map { it.first.id }

        assertThat(kept).containsExactly("comedy0", "comedy1")
    }

    @Test
    fun `one click does not make a channel known`() {
        val user = NeuroVectorMath.PreparedVector(vec("comedy" to 1.0))
        val clickedOnce = video("drift", channelId = "clicked") to vec("lottery" to 1.0)
        val onTopic = (0 until 6).map { video("comedy$it") to vec("comedy" to 1.0) }

        val kept =
            NeuroScoring
                .keepRelevant(onTopic + clickedOnce, user, NeuroScoring.LANE_RELEVANCE_FLOOR, emptySet(), mapOf("clicked" to 0.525))
                .map { it.first.id }

        assertThat(kept).doesNotContain("drift")
    }

    @Test
    fun `a like count the list never carried is no sign of clickbait`() {
        val popular = video("p", viewCount = 400_000L, uploadDate = "3 days ago")

        assertThat(NeuroScoring.calculateEngagementQuality(popular, isColdStart = false)).isEqualTo(1.0)
        assertThat(NeuroScoring.calculateEngagementQuality(popular.copy(likeCount = 100L), isColdStart = false)).isLessThan(0.5)
    }

    @Test
    fun `a video that says what it is is not second-guessed by its title`() {
        val song = video("s", title = "Track 7", duration = 200).copy(isMusic = true)
        val talk = video("t", title = "lofi remix explained", duration = 200).copy(category = "Education")
        val unknown = video("u", title = "lofi remix", duration = 200)

        assertThat(NeuroScoring.isMusicTrack(song)).isTrue()
        assertThat(NeuroScoring.isMusicTrack(talk)).isFalse()
        assertThat(NeuroScoring.isMusicTrack(talk.copy(category = "Music"))).isTrue()
        assertThat(NeuroScoring.isMusicTrack(unknown)).isTrue()
    }

    @Test
    fun `a long video is never a music track to rewatch`() {
        assertThat(NeuroScoring.isMusicTrack(video("l", duration = 3_600).copy(isMusic = true))).isFalse()
    }
}
