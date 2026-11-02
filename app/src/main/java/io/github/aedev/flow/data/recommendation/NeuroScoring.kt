/*
 * Copyright (C) 2025-2026 Flow | A-EDev
 *
 * This file is part of Flow (https://github.com/Flow-Tube/Flow).
 *
 * Flow is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This recommendation algorithm (FlowNeuroEngine) is the intellectual property
 * of the Flow project. Any use of this code in other projects must
 * explicitly credit "Flow Android Client" and link back to the original repository.
 */

package io.github.aedev.flow.data.recommendation

import io.github.aedev.flow.data.model.Video
import kotlin.math.*

/**
 * Scoring factor calculators. Each function computes one
 * signal and returns a multiplier or additive bonus.
 * All functions are pure — no state mutation.
 */
internal object NeuroScoring {
    // ── Scoring Weight Constants ──
    const val SUBSCRIPTION_BOOST = 0.15

    /** The reel feed already carries its own subscription lane; a triple boost here made every page one. */
    const val SHORTS_SUBSCRIPTION_BOOST_MULTIPLIER = 1.5
    const val SUBSCRIPTION_BOOST_MAX = 0.30

    /** Channel-boredom multiplier at the 0.5 starting EMA — the neutral point. */
    const val CHANNEL_SIGNAL_NEUTRAL = 0.7801
    const val SERENDIPITY_BONUS = 0.10
    const val CURIOSITY_GAP_BONUS = 0.10
    const val NOT_INTERESTED_CHANNEL_FLOOR = 0.20
    const val CHANNEL_EMA_ALPHA = 0.05
    const val CHANNEL_EMA_DECAY = 1.0 - CHANNEL_EMA_ALPHA

    /** A thumbs-down halves the channel's score; the EMA step moved it by 2.5 % (#907). */
    const val DISLIKE_CHANNEL_FACTOR = 0.5
    const val MAX_CHANNEL_SCORES = 500
    const val CHANNEL_KEEP_LOW = 50
    const val CHANNEL_KEEP_HIGH = 200
    const val SHORTS_LEARNING_PENALTY = 0.01
    const val MAX_CONSECUTIVE_SKIPS = 30
    const val SESSION_RESET_IDLE_MINUTES = 120L
    const val SESSION_RESET_EMPTY_MINUTES = 30L
    const val COLD_START_THRESHOLD = 30
    const val ONBOARDING_WARMUP_INTERACTIONS = 50
    const val ONBOARDING_MAX_BOOST = 0.15
    const val ENGAGEMENT_RATE_BASELINE = 0.05
    const val ENGAGEMENT_MAX_BOOST = 0.05
    const val ENGAGEMENT_MIN_VIEWS = 1000L
    const val ENGAGEMENT_FLOOR_RATE = 0.01
    const val ENGAGEMENT_FLOOR_MIN_VIEWS = 50_000L
    const val ENGAGEMENT_FLOOR_PENALTY = 0.2
    const val COLD_START_ENGAGEMENT_FLOOR_RATE = 0.02
    const val COLD_START_ENGAGEMENT_FLOOR_MIN_VIEWS = 10_000L
    const val BINGE_THRESHOLD = 20
    const val BINGE_NOVELTY_FACTOR = 0.15

    // Jitter is a FRACTION of the median candidate score (see rank()), not absolute.
    const val JITTER_COLD_START = 0.40
    const val JITTER_NORMAL = 0.05
    const val TITLE_SIMILARITY_STRICT = 0.55
    const val TITLE_SIMILARITY_RELAXED = 0.60
    const val CLASSIC_VIEW_THRESHOLD = 5_000_000L
    const val DIVERSITY_PHASE1_TARGET = 20
    const val ANTI_REC_PENALTY_THRESHOLD = 0.6
    const val ANTI_REC_PENALTY = 0.4
    const val MOMENTUM_WINDOW = 10
    const val MOMENTUM_BOOST = 0.08
    const val MOMENTUM_THRESHOLD = 3
    const val IMPRESSION_CACHE_MAX = 500
    const val IMPRESSION_DECAY_RATE = 0.1
    const val IMPRESSION_PENALTY_HEAVY = 0.05
    const val IMPRESSION_PENALTY_MEDIUM = 0.30
    const val IMPRESSION_PENALTY_LIGHT = 0.85
    const val IMPRESSION_THRESHOLD_DROP = 5
    const val IMPRESSION_THRESHOLD_HEAVY = 3
    const val IMPRESSION_THRESHOLD_LIGHT = 1
    const val MUSIC_REWATCH_MAX_DURATION = 480
    const val WATCHED_PENALTY_FULL = 0.02
    const val WATCHED_PENALTY_HALF = 0.30
    const val WATCHED_PENALTY_SAMPLED = 0.70
    const val WATCHED_THRESHOLD_FULL = 0.85f
    const val WATCHED_THRESHOLD_HALF = 0.50f
    const val WATCHED_THRESHOLD_SAMPLED = 0.15f
    const val WATCH_HISTORY_MAX = 2000
    const val SEEN_SHORTS_MAX = 3000
    const val SEEN_SHORT_PENALTY = 0.05
    const val SEEN_SHORT_EXPIRY_DAYS = 7

    // 0.03/co-watch: one shared session creates a cluster edge (min 0.02), five
    // reach the affinity-query threshold (0.15). The old 0.01 could never outlive
    // the per-update prune that used to sit at 0.05.
    const val AFFINITY_INCREMENT = 0.03
    const val AFFINITY_MAX = 1.0
    const val AFFINITY_MAX_ENTRIES = 500
    const val AFFINITY_KEEP_TOP = 300
    const val AFFINITY_MAX_BOOST_PER_VIDEO = 0.15
    const val AFFINITY_BOOST_PER_PAIR = 0.05
    const val CHANNEL_PROFILE_LEARNING_RATE = 0.1
    const val CHANNEL_PROFILE_MAX_TOPICS = 15
    const val CHANNEL_PROFILE_PRUNE_THRESHOLD = 0.05
    const val CHANNEL_PROFILE_MAX_CHANNELS = 200
    const val CHANNEL_PROFILE_BLEND_WEIGHT = 0.3
    const val CHANNEL_PROFILE_MIN_VIDEOS = 3
    const val NOT_INTERESTED_GLOBAL_RATE = -0.35
    const val NOT_INTERESTED_TIME_RATE = -0.25
    const val PERSONA_STABILITY_THRESHOLD = 3
    const val PERSONA_MAX_STABILITY = 10
    const val EXPLORATION_SCORE_THRESHOLD = 0.1
    const val EXPLORE_BETA_C = 0.28
    const val EXPLORE_MAX_BONUS = 0.08

    // ── IDF vocabulary bounds ──
    const val IDF_MAX_KEYS = 20_000
    const val IDF_KEEP_KEYS = 16_000

    // ── Novelty & Relevance Gate ──
    const val NOVELTY_RELEVANCE_GATE = 0.08

    const val RELEVANCE_FLOOR_MIN_INTERACTIONS = 80

    const val RELEVANCE_FLOOR_SEVERE_THRESHOLD = 0.05
    const val RELEVANCE_FLOOR_MODERATE_THRESHOLD = 0.10
    const val RELEVANCE_FLOOR_SEVERE_PENALTY = 0.15
    const val RELEVANCE_FLOOR_MODERATE_PENALTY = 0.40

    /** Long-form videos skipped in a row before the feed gets an extra exploration slot. */
    const val BOREDOM_SKIPS = 5

    /** Topic overlap a Home lane needs from a candidate: below it the video shares nothing with your taste. */
    const val LANE_RELEVANCE_FLOOR = 0.02

    // Below this specificity-weighted overlap a video shares nothing but common words with the viewer (#907).
    const val UNRELATED_SIMILARITY = 0.002

    // A channel the viewer has taken to more than once; one click on a padded card moves it to only 0.525 (#907).
    const val KNOWN_CHANNEL_SCORE = 0.6

    const val EXPLORATION_MIN_SCORE_RATIO = 0.30

    // ── Feed History Constants ──
    const val FEED_HISTORY_MAX = 3000
    const val FEED_HISTORY_EXPIRY_DAYS = 14L

    // ── Hard seen-gate (repetition filter, not penalty) ──
    const val SEEN_GATE_SHOW_COUNT = 2
    const val SEEN_GATE_WINDOW_HOURS = 60.0

    /** Even a single viewport impression hides an item for this short window. */
    const val SEEN_GATE_SINGLE_SHOW_WINDOW_HOURS = 6.0
    const val SEEN_GATE_MIN_POOL = 25
    const val SEEN_GATE_MIN_RESULTS = 10

    // ── Related-seed rotation ──
    const val RELATED_SEED_COOLDOWN_HOURS = 6L
    const val RECENT_RELATED_SEEDS_MAX = 60

    // ── Stale-query memory (result novelty, not query wording) ──
    const val STALE_QUERY_NOVELTY_THRESHOLD = 0.4
    const val STALE_QUERY_EXPIRY_HOURS = 24L
    const val STALE_QUERY_MAX = 40

    // ── Interest-cluster rotation state cap ──
    const val CLUSTER_ROTATION_MAX = 40

    /** Clusters contributing to EVERY feed; staleness rotation gates only the tail beyond this. */
    const val MAX_CLUSTERS_PER_REFRESH = 6

    /** Top-mass clusters guaranteed a slot in every feed regardless of staleness. */
    const val MAJOR_CLUSTER_SLOTS = 3

    // ── Topic acquisition floor (strong signals plant new interests) ──
    const val TOPIC_ACQUISITION_FLOOR = 0.05
    const val TOPIC_ACQUISITION_TOP_K = 3

    // ── Tag co-occurrence edges (from opened videos' real tags) ──
    const val TAG_AFFINITY_TOKENS = 6
    const val TAG_AFFINITY_INCREMENT = 0.05
    const val TAG_AFFINITY_MAX_ENTRIES = 400
    const val TAG_AFFINITY_KEEP_TOP = 300

    // ── Implicit Disinterest Constants ──
    const val IMPLICIT_DISINTEREST_WINDOW_HOURS = 48.0
    const val IMPLICIT_DISINTEREST_THRESHOLD_HEAVY = 5
    const val IMPLICIT_DISINTEREST_THRESHOLD_LIGHT = 3
    const val IMPLICIT_DISINTEREST_PENALTY_HEAVY = 0.10
    const val IMPLICIT_DISINTEREST_PENALTY_LIGHT = 0.30

    // ── Query Rotation Constants ──
    const val RECENT_QUERY_TOKENS_MAX = 20
    const val QUERY_OVERLAP_THRESHOLD = 0.4

    // ── Rejection Pattern Memory ──

    /** A rejection's weight halves every month instead of vanishing after two weeks. */
    const val REJECTION_HALF_LIFE_DAYS = 30.0
    const val REJECTION_FORGET_BELOW = 0.5
    const val REJECTION_MEMORY_MAX = 200
    const val REJECTION_PENALTY_1 = 0.50
    const val REJECTION_PENALTY_2 = 0.20
    const val REJECTION_PENALTY_3_PLUS = 0.05
    private const val REJECTION_RECORD_KEYS = 2
    private const val REJECTION_MATCH_TOPICS = 6
    private const val REJECTION_WORD_PREFIX = "~"

    /** A "~" pattern counts once a second rejection repeats it. */
    private const val REJECTION_REPEATED = 1.5

    /** Words these lists compare keys with; key spec 2 never stems them ([NeuroStemmer]). */
    internal val CURATED_WORDS: Set<String> get() = REJECTION_BROAD_TOPICS + MUSIC_KEYWORDS

    private val REJECTION_BROAD_TOPICS =
        hashSetOf(
            "music",
            "game",
            "video",
            "sport",
            "food",
            "art",
            "tech",
            "science",
            "news",
            "show",
            "movie",
            "film",
            "learn",
            "education",
            "entertainment",
            "review",
            "react",
            "challenge",
            "build",
            "design",
            "travel",
            // Hans keys.
            "新闻",
            "音乐",
            "游戏",
            "科技",
            "电影",
            "教育",
            "美食",
            "旅游",
        )

    // ── Time Decay Engine ──

    object TimeDecay {
        fun calculateMultiplier(
            dateText: String,
            isLive: Boolean,
        ): Double {
            val text = dateText.lowercase()
            if (isLive) return 1.15

            return when {
                text.contains("second") || text.contains("minute") ||
                    text.contains("hour") -> {
                    1.15
                }

                text.contains("day") -> {
                    1.12
                }

                text.contains("week") -> {
                    1.08
                }

                text.contains("month") -> {
                    val months = text.filter { it.isDigit() }.toIntOrNull() ?: 1
                    (1.0 / (1.0 + 0.08 * months)).coerceAtLeast(0.75)
                }

                text.contains("year") -> {
                    val years = text.filter { it.isDigit() }.toIntOrNull() ?: 1
                    1.0 / (1.0 + (0.35 * years))
                }

                else -> {
                    0.85
                }
            }
        }

        fun isOlderThan24Hours(dateText: String): Boolean {
            val text = dateText.lowercase()
            return when {
                text.contains("second") || text.contains("minute") ||
                    text.contains("hour") -> false

                text.contains("day") || text.contains("week") ||
                    text.contains("month") || text.contains("year") -> true

                else -> true
            }
        }
    }

    // ── Music Detection ──

    private val MUSIC_KEYWORDS =
        setOf(
            "music",
            "song",
            "lyrics",
            "remix",
            "lofi",
            "lo-fi",
            "playlist",
            "official audio",
            "official video",
            "music video",
            "feat",
            "ft.",
            "acoustic",
            "cover",
            "karaoke",
            "instrumental",
            "beat",
            "rap",
            "hip hop",
            "pop",
            "rock",
            "jazz",
            "classical",
            "edm",
            "mix",
        )

    fun isMusicTrack(video: Video): Boolean {
        if (video.duration > MUSIC_REWATCH_MAX_DURATION) return false
        val titleLower = video.title.lowercase()
        val channelLower = video.channelName.lowercase()
        return MUSIC_KEYWORDS.any { keyword ->
            titleLower.contains(keyword) || channelLower.contains(keyword)
        }
    }

    fun isVideoClassic(viewCount: Long): Boolean = viewCount >= CLASSIC_VIEW_THRESHOLD

    /**
     * Unified channel signal combining subscription boost and channel boredom.
     *
     * V9.3 Fix 4: Channel boredom uses a sigmoid curve instead of a hard
     * threshold. Smooth transition: 0% click rate → 0.4x, 5% → 0.7x,
     * 10% → 0.9x, 20%+ → ~1.0x.
     */
    fun calculateChannelSignal(
        video: Video,
        brain: UserBrain,
        userSubs: Set<String>,
    ): Double {
        var signal = 0.0

        // Subscription boost with freshness amplifier
        val isSub = userSubs.contains(video.channelId)
        if (isSub) {
            val subBoost = if (video.isShort) SUBSCRIPTION_BOOST * SHORTS_SUBSCRIPTION_BOOST_MULTIPLIER else SUBSCRIPTION_BOOST

            val freshnessMultiplier =
                when {
                    !TimeDecay.isOlderThan24Hours(video.uploadDate) -> 2.0

                    video.uploadDate.lowercase().let { text ->
                        text.contains("day") &&
                            (text.filter { it.isDigit() }.toIntOrNull() ?: 99) <= 2
                    } -> 1.5

                    video.uploadDate.lowercase().let { text ->
                        text.contains("week") &&
                            (text.filter { it.isDigit() }.toIntOrNull() ?: 99) <= 1
                    } -> 1.2

                    else -> 1.0
                }

            signal += (subBoost * freshnessMultiplier).coerceAtMost(SUBSCRIPTION_BOOST_MAX)
        }

        // V9.3 Fix 4 + V11 recenter: sigmoid channel boredom, neutral at the EMA
        // start (0.5). The old form subtracted 1.0, which handed EVERY known
        // channel an additive penalty (-0.22 at the 0.5 starting EMA) and made
        // familiar channels rank below never-seen ones for ~20 interactions.
        if (brain.channelScores.containsKey(video.channelId)) {
            val channelClickRate = brain.channelScores[video.channelId] ?: 0.5
            val channelQuality = 1.0 / (1.0 + exp(-8.0 * (channelClickRate - 0.35)))
            val channelMultiplier = 0.05 + 0.95 * channelQuality
            signal += (channelMultiplier - CHANNEL_SIGNAL_NEUTRAL)
        }

        return signal
    }

    /**
     * Unified engagement quality signal combining positive boost and
     * clickbait floor filter.
     */
    fun calculateEngagementQuality(
        video: Video,
        isColdStart: Boolean,
    ): Double {
        val views = video.viewCount
        val likes = video.likeCount

        // Search, related and feed lists carry no like count, so 0 means unknown, not a clickbait ratio (#907).
        if (views < ENGAGEMENT_MIN_VIEWS || likes <= 0) return 1.0

        val rate = likes.toDouble() / views.toDouble()

        // ── Floor: clickbait filter ──
        val floorMinViews =
            if (isColdStart) {
                COLD_START_ENGAGEMENT_FLOOR_MIN_VIEWS
            } else {
                ENGAGEMENT_FLOOR_MIN_VIEWS
            }
        val floorRate =
            if (isColdStart) {
                COLD_START_ENGAGEMENT_FLOOR_RATE
            } else {
                ENGAGEMENT_FLOOR_RATE
            }

        if (views > floorMinViews &&
            TimeDecay.isOlderThan24Hours(video.uploadDate) &&
            rate < floorRate
        ) {
            // V9.3 Fix 4: Graduated penalty instead of cliff
            return (
                ENGAGEMENT_FLOOR_PENALTY +
                    (1.0 - ENGAGEMENT_FLOOR_PENALTY) * (rate / floorRate)
            ).coerceIn(ENGAGEMENT_FLOOR_PENALTY, 1.0)
        }

        // ── Boost: high engagement ──
        val boost =
            (rate / ENGAGEMENT_RATE_BASELINE)
                .coerceIn(0.0, 1.0) * ENGAGEMENT_MAX_BOOST
        return 1.0 + boost
    }

    /**
     * Unified freshness factor combining:
     * - Session topic repetition (fatigue)
     * - Impression fatigue (exponentially decaying)
     * - Session momentum (positive for 1-2 repeats, negative for 3+)
     * - Binge novelty injection (when session is long)
     */
    fun calculateFreshness(
        video: Video,
        videoVector: ContentVector,
        personalityScore: Double,
        sessionTopics: List<String>,
        sessionVideoCount: Int,
        impressionEntry: ImpressionEntry?,
        now: Long,
    ): Double {
        val primaryTopic = videoVector.topics.maxByOrNull { it.value }?.key ?: ""

        // ── Session topic momentum ──
        val topicSessionCount =
            if (primaryTopic.isNotEmpty()) {
                sessionTopics.count { it == primaryTopic }
            } else {
                0
            }

        val momentumFactor =
            if (topicSessionCount > 0 && primaryTopic.isNotEmpty()) {
                exp(-0.16 * topicSessionCount).coerceIn(0.15, 1.0)
            } else {
                1.0
            }

        // ── Impression decay ──
        val impressionFactor =
            if (impressionEntry != null) {
                val hoursSince = (now - impressionEntry.lastSeen) / 3_600_000.0
                val decayedCount =
                    (
                        impressionEntry.count *
                            exp(-IMPRESSION_DECAY_RATE * hoursSince)
                    ).toInt()
                when {
                    decayedCount >= IMPRESSION_THRESHOLD_DROP -> IMPRESSION_PENALTY_HEAVY
                    decayedCount >= IMPRESSION_THRESHOLD_HEAVY -> IMPRESSION_PENALTY_MEDIUM
                    decayedCount >= IMPRESSION_THRESHOLD_LIGHT -> IMPRESSION_PENALTY_LIGHT
                    else -> 1.0
                }
            } else {
                1.0
            }

        // ── Binge novelty injection ──
        val bingeFactor =
            if (sessionVideoCount > BINGE_THRESHOLD) {
                val noveltyScore = 1.0 - personalityScore
                1.0 + (noveltyScore * BINGE_NOVELTY_FACTOR)
            } else {
                1.0
            }

        return (momentumFactor * impressionFactor * bingeFactor)
            .coerceIn(0.05, 1.3)
    }

    /**
     * V9.3 Fix 4: Smooth curiosity gap with graduated ramps.
     */
    fun calculateCuriosityBonus(
        personalityScore: Double,
        brainComplexity: Double,
        videoComplexity: Double,
    ): Double {
        if (personalityScore <= 0.5) return 0.0

        val complexityDiff = abs(brainComplexity - videoComplexity)
        if (complexityDiff <= 0.2) return 0.0

        val curiosityRamp = ((complexityDiff - 0.2) / 0.3).coerceIn(0.0, 1.0)
        val topicSafety = ((personalityScore - 0.5) / 0.3).coerceIn(0.0, 1.0)
        return CURIOSITY_GAP_BONUS * curiosityRamp * topicSafety
    }

    /**
     * V9.3 Fix 4: Smooth serendipity with graduated ramps.
     */
    fun calculateSerendipity(
        noveltyScore: Double,
        contextScore: Double,
    ): Double {
        val noveltyRamp = ((noveltyScore - 0.4) / 0.4).coerceIn(0.0, 1.0)
        val contextRamp = ((contextScore - 0.3) / 0.4).coerceIn(0.0, 1.0)
        return SERENDIPITY_BONUS * noveltyRamp * contextRamp
    }

    /**
     * Already-watched penalty with music exception.
     */
    fun calculateWatchedPenalty(
        video: Video,
        watchEntry: WatchEntry?,
    ): Double {
        if (watchEntry == null) return 1.0

        val isMusic = isMusicTrack(video)
        return when {
            isMusic && watchEntry.percentWatched > WATCHED_THRESHOLD_HALF -> 1.0
            watchEntry.percentWatched > WATCHED_THRESHOLD_FULL -> WATCHED_PENALTY_FULL
            watchEntry.percentWatched > WATCHED_THRESHOLD_HALF -> WATCHED_PENALTY_HALF
            watchEntry.percentWatched > WATCHED_THRESHOLD_SAMPLED -> WATCHED_PENALTY_SAMPLED
            else -> 1.0
        }
    }

    /**
     * Anti-recommendation penalty for videos matching negatively-rated channel profiles.
     */
    fun calculateAntiRecommendationPenalty(
        videoVector: ContentVector,
        video: Video,
        brain: UserBrain,
    ): Double {
        val negativeChannels =
            brain.channelScores
                .filter { (_, score) -> score < NOT_INTERESTED_CHANNEL_FLOOR }
                .keys

        if (negativeChannels.isEmpty()) return 1.0

        val negativeProfiles =
            negativeChannels.mapNotNull { channelId ->
                brain.channelTopicProfiles[channelId]
            }

        if (negativeProfiles.isEmpty()) return 1.0

        var maxSimilarity = 0.0
        negativeProfiles.forEach { negProfile ->
            val negVector = ContentVector(topics = negProfile)
            val similarity = NeuroVectorMath.calculateCosineSimilarity(negVector, videoVector)
            if (similarity > maxSimilarity) {
                maxSimilarity = similarity
            }
        }

        return if (maxSimilarity > ANTI_REC_PENALTY_THRESHOLD) {
            val penaltyStrength =
                (
                    (maxSimilarity - ANTI_REC_PENALTY_THRESHOLD) /
                        (1.0 - ANTI_REC_PENALTY_THRESHOLD)
                ).coerceIn(0.0, 1.0)
            1.0 - (penaltyStrength * (1.0 - ANTI_REC_PENALTY))
        } else {
            1.0
        }
    }

    /**
     * Session-level engagement momentum boost.
     * personalityScore: if the topic already scores high, reduce momentum
     * so dominant topics don't get double-boosted.
     */
    fun calculateMomentumBoost(
        videoVector: ContentVector,
        interactions: List<MomentumEntry>,
        personalityScore: Double = 0.0,
    ): Double {
        if (interactions.size < MOMENTUM_THRESHOLD) return 0.0

        val primaryTopic =
            videoVector.topics.maxByOrNull { it.value }?.key
                ?: return 0.0

        val recentPositiveCount =
            interactions
                .takeLast(MOMENTUM_WINDOW)
                .count { it.topic == primaryTopic && it.positive }

        if (recentPositiveCount < MOMENTUM_THRESHOLD) return 0.0

        val rawBoost =
            (recentPositiveCount.toDouble() / MOMENTUM_WINDOW * MOMENTUM_BOOST)
                .coerceAtMost(MOMENTUM_BOOST)

        val dominancePenalty =
            if (personalityScore > 0.6) {
                (1.0 - (personalityScore - 0.6) / 0.4).coerceIn(0.0, 1.0)
            } else {
                1.0
            }

        return rawBoost * dominancePenalty
    }

    /**
     * Cross-session repetition prevention. Penalizes videos the user has
     * already seen in their feed, even if they never clicked on them.
     *
     * Uses graduated time-based recovery so videos naturally return.
     * Relaxes penalties when the candidate pool is small to prevent
     * empty feeds for users with narrow interests.
     */
    fun calculateFeedHistoryPenalty(
        videoId: String,
        feedHistory: Map<String, FeedEntry>,
        now: Long,
        candidatePoolSize: Int,
    ): Double {
        val entry = feedHistory[videoId] ?: return 1.0
        val hoursSince = (now - entry.lastShown) / 3_600_000.0

        // Scarcity relaxation: blend toward 1.0 when candidate pool is small
        val scarcityRelaxation =
            when {
                candidatePoolSize < 10 -> 0.4
                candidatePoolSize < 25 -> 0.7
                else -> 1.0
            }

        // Heavier penalty for videos shown many times
        val countMultiplier =
            when {
                entry.showCount >= 5 -> 0.7
                entry.showCount >= 3 -> 0.85
                else -> 1.0
            }

        val basePenalty =
            (
                when {
                    hoursSince < 2.0 -> 0.05
                    hoursSince < 8.0 -> 0.15
                    hoursSince < 24.0 -> 0.35
                    hoursSince < 72.0 -> 0.60
                    hoursSince < 168.0 -> 0.80
                    hoursSince < 336.0 -> 0.92
                    else -> 1.0
                } * countMultiplier
            ).coerceIn(0.0, 1.0)

        // Blend toward 1.0 when pool is scarce
        return basePenalty + (1.0 - basePenalty) * (1.0 - scarcityRelaxation)
    }

    // ── Precision topic blocking ──

    data class BlockedMatchers(
        val phrases: Set<String>,
        val tokens: Set<String>,
    ) {
        fun isEmpty(): Boolean = phrases.isEmpty() && tokens.isEmpty()
    }

    /**
     * A block is precise: the term and its lemma, token-matched. Expanding to a
     * whole catalog category happens ONLY when the user blocked the category
     * NAME itself — blocking "Fortnite" must not silently erase all of Gaming.
     */
    fun buildBlockedMatchers(
        blockedTopics: Set<String>,
        categories: List<TopicCategory>,
        normalizeLemma: (String) -> String,
    ): BlockedMatchers {
        val phrases = mutableSetOf<String>()
        val tokens = mutableSetOf<String>()

        fun addTerm(term: String) {
            val lower = NeuroText.fold(term, HanFold.ALWAYS).trim()
            if (lower.isEmpty()) return
            if (lower.contains(' ')) {
                phrases += lower
            } else {
                tokens += lower
                tokens += normalizeLemma(lower)
            }
        }

        blockedTopics.forEach { blocked ->
            val blockedLower = blocked.lowercase()
            categories
                .find { it.name.lowercase() == blockedLower }
                ?.topics
                ?.forEach(::addTerm)
            addTerm(blockedLower)
        }
        return BlockedMatchers(phrases, tokens)
    }

    /** Token-boundary matching: blocking "art" hides "art", never "startup". */
    fun isBlockedByText(
        title: String,
        channelName: String,
        matchers: BlockedMatchers,
        normalizeLemma: (String) -> String,
    ): Boolean {
        if (matchers.isEmpty()) return false
        val titleLower = NeuroText.fold(title, HanFold.ALWAYS)
        val channelLower = NeuroText.fold(channelName, HanFold.ALWAYS)
        if (matchers.phrases.any { titleLower.contains(it) || channelLower.contains(it) }) return true
        if (matchers.tokens.isEmpty()) return false
        return sequenceOf(titleLower, channelLower)
            .flatMap { it.splitToSequence(' ', '-', '_', '|', ',', '.', ':', '(', ')', '[', ']', '#') }
            .map { it.trim { c -> !c.isLetterOrDigit() } }
            .filter { it.length > 1 }
            .any { token -> token in matchers.tokens || normalizeLemma(token) in matchers.tokens }
    }

    /**
     * Hard seen-gate: industry practice (Twitter home-mixer's "previously seen
     * removal") treats recent repeats as a FILTER, not a score penalty — a
     * penalty lets high-scoring items punch back into the feed. Items shown
     * [SEEN_GATE_SHOW_COUNT]+ times within [SEEN_GATE_WINDOW_HOURS] are always
     * dropped (#1565). The single-sighting hide keeps two scarcity guards: small
     * pools skip it, and if it would leave fewer than [SEEN_GATE_MIN_RESULTS]
     * items it backs off.
     */
    fun <T> applySeenGate(
        items: List<T>,
        feedHistory: Map<String, FeedEntry>,
        now: Long,
        idOf: (T) -> String,
    ): List<T> {
        if (feedHistory.isEmpty()) return items
        val unrepeated = items.filterNot { item -> isRepeatedlySeen(feedHistory[idOf(item)], now) }
        if (unrepeated.size < SEEN_GATE_MIN_POOL) return unrepeated
        val kept = unrepeated.filterNot { item -> isRecentlySeen(feedHistory[idOf(item)], now) }
        return if (kept.size >= SEEN_GATE_MIN_RESULTS) kept else unrepeated
    }

    /** Shown [SEEN_GATE_SHOW_COUNT]+ times and still inside the long hide. */
    fun isRepeatedlySeen(
        entry: FeedEntry?,
        now: Long,
    ): Boolean {
        entry ?: return false
        if (entry.showCount < SEEN_GATE_SHOW_COUNT) return false
        return (now - entry.lastShown) / 3_600_000.0 < SEEN_GATE_WINDOW_HOURS
    }

    /** The seen-gate's rule for one item, without its scarcity guards. */
    fun isRecentlySeen(
        entry: FeedEntry?,
        now: Long,
    ): Boolean {
        entry ?: return false
        val hoursSince = (now - entry.lastShown) / 3_600_000.0
        // A single impression hides the item briefly (kills the classic
        // "same video on every refresh"); repeats hide it for days.
        if (hoursSince < SEEN_GATE_SINGLE_SHOW_WINDOW_HOURS) return true
        if (entry.showCount < SEEN_GATE_SHOW_COUNT) return false
        return hoursSince < SEEN_GATE_WINDOW_HOURS
    }

    /**
     * Implicit disinterest signal. Videos shown multiple times in a short
     * window but never watched are implicitly uninteresting.
     *
     * This is softer than explicit "not interested" — it just deprioritizes
     * rather than suppressing, and only triggers on clear patterns.
     */
    fun calculateImplicitDisinterestPenalty(
        videoId: String,
        feedHistory: Map<String, FeedEntry>,
        watchHistory: Map<String, WatchEntry>,
        now: Long,
    ): Double {
        val entry = feedHistory[videoId] ?: return 1.0

        if (watchHistory.containsKey(videoId)) return 1.0

        val hoursSince = (now - entry.lastShown) / 3_600_000.0

        if (hoursSince > IMPLICIT_DISINTEREST_WINDOW_HOURS) return 1.0

        return when {
            entry.showCount >= IMPLICIT_DISINTEREST_THRESHOLD_HEAVY -> {
                IMPLICIT_DISINTEREST_PENALTY_HEAVY
            }

            entry.showCount >= IMPLICIT_DISINTEREST_THRESHOLD_LIGHT -> {
                IMPLICIT_DISINTEREST_PENALTY_LIGHT
            }

            else -> {
                1.0
            }
        }
    }

    /**
     * Adaptive jitter based on feed staleness, expressed as a FRACTION of the
     * median candidate score (the caller multiplies). When most candidates were
     * recently shown, randomization rises to break deterministic ordering; with
     * fresh candidates it drops so quality ranking dominates. Relative scaling
     * keeps noise proportional — it can no longer drown the signal outright.
     */
    fun calculateAdaptiveJitter(
        totalInteractions: Int,
        feedOverlapRatio: Double,
    ): Double =
        when {
            totalInteractions < ONBOARDING_WARMUP_INTERACTIONS -> {
                JITTER_COLD_START
            }

            feedOverlapRatio > 0.5 -> {
                0.25
            }

            feedOverlapRatio > 0.2 -> {
                0.12
            }

            else -> {
                JITTER_NORMAL
            }
        }

    /**
     * Relevance floor for mature brains. When the algorithm has enough
     * data to know what the user likes, content with near-zero topical
     * similarity gets a steep penalty.
     *
     * Subscription content is exempt — you subscribed, so it's relevant
     * by definition. Cold start users are exempt — not enough data yet.
     */
    fun calculateRelevanceFloor(
        personalityScore: Double,
        totalInteractions: Int,
        isSubscription: Boolean,
    ): Double {
        if (isSubscription) return 1.0
        if (totalInteractions < RELEVANCE_FLOOR_MIN_INTERACTIONS) return 1.0

        return when {
            personalityScore < RELEVANCE_FLOOR_SEVERE_THRESHOLD -> {
                RELEVANCE_FLOOR_SEVERE_PENALTY
            }

            personalityScore < RELEVANCE_FLOOR_MODERATE_THRESHOLD -> {
                RELEVANCE_FLOOR_MODERATE_PENALTY
            }

            else -> {
                1.0
            }
        }
    }

    /**
     * Strips the domain tag from a domain-disambiguated topic.
     * e.g. "metal:music" → "metal", "rock:climbing" → "rock", "jazz" → "jazz"
     */
    fun stripDomainTag(topic: String): String {
        val colonIndex = topic.indexOf(':')
        return if (colonIndex > 0) topic.substring(0, colonIndex) else topic
    }

    // ── Rejection Pattern Memory Functions ──

    /**
     * What a rejection is remembered by. Only the pair of the two strongest topics counts at once:
     * one rejected video says little about any single topic, and IDF weighting makes a title's rare
     * words ("late", "drives") its strongest. The two strongest specific topics and the words of the
     * top four are remembered as "~" patterns, which count once a second rejection repeats them, so
     * the topic rejected videos share ("phonk") is what builds up (#907).
     */
    fun extractRejectionKeys(videoVector: ContentVector): List<String> {
        val topTopics =
            videoVector.topics.entries
                .sortedByDescending { it.value }
                .take(4)
                .map { stripDomainTag(it.key) }
                .filter(NeuroText::isTopicSized)
                .distinct()
        if (topTopics.isEmpty()) return emptyList()

        val keys = mutableListOf<String>()
        if (topTopics.size >= 2) keys += makeAffinityKey(topTopics[0], topTopics[1])
        val specific = topTopics.filter { it !in REJECTION_BROAD_TOPICS }.take(REJECTION_RECORD_KEYS)
        val words =
            topTopics
                .flatMap { it.split(' ') }
                .filter { NeuroText.isTopicSized(it) && it !in REJECTION_BROAD_TOPICS }
        (specific + words).distinct().mapTo(keys) { REJECTION_WORD_PREFIX + it }
        return keys
    }

    /**
     * The terms a candidate can be recognised by: its strongest topics and the words of its phrases.
     * A rejected "phonk" must catch "phonk mix" and "drift phonk", not only a title whose single
     * strongest key is exactly "phonk" (#907).
     */
    private fun rejectionTerms(videoVector: ContentVector): Set<String> {
        val top =
            videoVector.topics.entries
                .sortedByDescending { it.value }
                .take(REJECTION_MATCH_TOPICS)
                .map { stripDomainTag(it.key) }
        val terms = HashSet<String>(top)
        top.filter { ' ' in it }.forEach { phrase ->
            phrase.split(' ').filterTo(terms) { NeuroText.isTopicSized(it) && it !in REJECTION_BROAD_TOPICS }
        }
        return terms
    }

    private fun matchesRejection(
        key: String,
        terms: Set<String>,
    ): Boolean {
        val pair = key.indexOf('|')
        return when {
            pair >= 0 -> key.substring(0, pair) in terms && key.substring(pair + 1) in terms
            key.startsWith(REJECTION_WORD_PREFIX) -> key.substring(REJECTION_WORD_PREFIX.length) in terms
            else -> key in terms
        }
    }

    /** The rejection count left after [now] - lastRejectedAt of monthly halving. */
    fun effectiveRejections(
        signal: RejectionSignal,
        now: Long,
    ): Double {
        val ageDays = (now - signal.lastRejectedAt).coerceAtLeast(0L) / 86_400_000.0
        return signal.count * 0.5.pow(ageDays / REJECTION_HALF_LIFE_DAYS)
    }

    private fun strongestRejection(
        videoVector: ContentVector,
        rejectionPatterns: Map<String, RejectionSignal>,
        now: Long,
    ): Double {
        val terms = rejectionTerms(videoVector)
        var strongest = 0.0
        rejectionPatterns.forEach { (key, signal) ->
            if (matchesRejection(key, terms)) {
                val effective = effectiveRejections(signal, now)
                val counts = !key.startsWith(REJECTION_WORD_PREFIX) || effective >= REJECTION_REPEATED
                if (counts) strongest = maxOf(strongest, effective)
            }
        }
        return strongest
    }

    fun calculateRejectionPatternPenalty(
        videoVector: ContentVector,
        rejectionPatterns: Map<String, RejectionSignal>,
        now: Long,
    ): Double {
        if (rejectionPatterns.isEmpty()) return 1.0
        val strength = strongestRejection(videoVector, rejectionPatterns, now)
        return when {
            strength >= 2.5 -> REJECTION_PENALTY_3_PLUS
            strength >= 1.5 -> REJECTION_PENALTY_2
            strength >= REJECTION_FORGET_BELOW -> REJECTION_PENALTY_1
            else -> 1.0
        }
    }

    fun getRejectionAggressionFactor(
        videoVector: ContentVector,
        rejectionPatterns: Map<String, RejectionSignal>,
        now: Long,
    ): Double {
        if (rejectionPatterns.isEmpty()) return 0.5
        val strength = strongestRejection(videoVector, rejectionPatterns, now)
        return when {
            strength >= 1.5 -> 0.10
            strength >= REJECTION_FORGET_BELOW -> 0.25
            else -> 0.50
        }
    }

    /**
     * Adds one rejection of [videoVector]'s patterns and forgets the ones whose weight has decayed
     * away. Both "Not interested" and a thumbs-down write here (#907).
     */
    fun recordRejection(
        rejectionPatterns: Map<String, RejectionSignal>,
        videoVector: ContentVector,
        now: Long,
    ): Map<String, RejectionSignal> {
        val updated = rejectionPatterns.toMutableMap()
        val keys = extractRejectionKeys(videoVector)
        keys.distinct().forEach { key ->
            val left = updated[key]?.let { effectiveRejections(it, now) } ?: 0.0
            updated[key] = RejectionSignal(count = (left + 1).roundToInt().coerceAtLeast(1), lastRejectedAt = now)
        }
        updated.entries.removeAll { effectiveRejections(it.value, now) < REJECTION_FORGET_BELOW }
        if (updated.size > REJECTION_MEMORY_MAX) {
            updated.entries
                .sortedBy { it.value.lastRejectedAt }
                .take(updated.size - REJECTION_MEMORY_MAX)
                .forEach { updated.remove(it.key) }
        }
        return updated
    }

    /** Fewer fresh discovery queries than this and the recently used ones fill in behind them. */
    const val MIN_ROTATED_QUERIES = 4

    /**
     * Queries not used recently first. When too few are fresh, the recent ones follow instead of
     * being dropped: keeping only the fresh ones shrank a refresh's discovery to two searches and
     * the feed to half its size after a few refreshes in a row.
     */
    fun rotateQueries(
        candidates: List<String>,
        recentQueryTokens: List<Set<String>>,
        tokenize: (String) -> Set<String>,
    ): List<String> {
        val fresh =
            candidates.filter { query ->
                val tokens = tokenize(query)
                if (tokens.isEmpty()) return@filter true
                recentQueryTokens.none { recent ->
                    if (recent.isEmpty()) return@none false
                    tokens.intersect(recent).size.toDouble() / tokens.union(recent).size > QUERY_OVERLAP_THRESHOLD
                }
            }
        return if (fresh.size >= MIN_ROTATED_QUERIES) fresh else fresh + candidates.filterNot { it in fresh }
    }

    /** Events a time bucket needs before its vector weighs as much as the whole profile. */
    const val TIME_BUCKET_CONFIDENT_EVENTS = 30

    fun timeBucketConfidence(
        brain: UserBrain,
        bucket: TimeBucket,
    ): Double = ((brain.timeBucketCounts[bucket] ?: 0).toDouble() / TIME_BUCKET_CONFIDENT_EVENTS).coerceIn(0.0, 1.0)

    // ── Topic affinity key ──

    fun makeAffinityKey(
        t1: String,
        t2: String,
    ): String = if (t1 < t2) "$t1|$t2" else "$t2|$t1"

    /** Hermite smoothstep — continuous ramp from 0 at edge0 to 1 at edge1. */
    private fun smoothstep(
        edge0: Double,
        edge1: Double,
        x: Double,
    ): Double {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    /** LFU vocabulary cap: when IDF exceeds the limit, keep only the most frequent keys. */
    fun capIdfVocabulary(freq: MutableMap<String, Int>) {
        if (freq.size <= IDF_MAX_KEYS) return
        val kept =
            freq.entries
                .sortedByDescending { it.value }
                .take(IDF_KEEP_KEYS)
                .associate { it.key to it.value }
        freq.clear()
        freq.putAll(kept)
    }

    /**
     * Candidates that share a topic with [user] or come from a channel the viewer subscribes to or
     * has taken to, so a lane cannot fill its slots with videos that have nothing to do with the
     * viewer (#907). A thin lane stays thin: Home looks for more on the viewer's subjects instead.
     */
    fun keepRelevant(
        candidates: List<Pair<Video, ContentVector>>,
        user: NeuroVectorMath.PreparedVector,
        minRelevance: Double,
        userSubs: Set<String>,
        channelScores: Map<String, Double>,
        specificity: ((String) -> Double)? = null,
    ): List<Pair<Video, ContentVector>> {
        fun knownChannel(channelId: String) = channelId in userSubs || (channelScores[channelId] ?: 0.0) >= KNOWN_CHANNEL_SCORE
        return candidates.filter { (video, vector) ->
            knownChannel(video.channelId) || NeuroVectorMath.topicSimilarity(user, vector, specificity) >= minRelevance
        }
    }

    /**
     * Cluster-diversified seed pick with PROGRESSIVE fill: pass 1 takes at most
     * one seed per cluster (spread-first, so with community-mapped keys each
     * major interest gets a related seed before any interest gets two); later
     * passes relax up to maxPerCluster only when slots remain.
     */
    fun pickDiverseSeeds(
        seeds: List<SeedRank>,
        maxSeeds: Int,
        maxPerCluster: Int,
        capOf: (String) -> Int = { maxPerCluster },
    ): List<String> {
        val sortedSeeds = seeds.sortedByDescending { it.weight }
        val out = LinkedHashSet<String>()
        val perCluster = HashMap<String, Int>()
        for (allowed in 1..maxPerCluster.coerceAtLeast(1)) {
            for (s in sortedSeeds) {
                if (out.size >= maxSeeds) return out.toList()
                if (s.id in out) continue
                val count = perCluster[s.clusterKey] ?: 0
                if (count >= minOf(allowed, capOf(s.clusterKey))) continue
                out.add(s.id)
                perCluster[s.clusterKey] = count + 1
            }
        }
        return out.toList()
    }

    // ── Topic probation (damp brand-new, unconfirmed topics on mature brains) ──

    private fun topicScore(
        brain: UserBrain,
        topic: String,
    ): Double {
        brain.globalVector.topics[topic]?.let { return it }
        return brain.globalVector.topics.entries
            .firstOrNull { stripDomainTag(it.key) == topic }
            ?.value ?: 0.0
    }

    private fun hasConfirmedTopicEvidence(
        topic: String,
        brain: UserBrain,
        lemmatizedPreferred: Set<String>,
    ): Boolean {
        val base = stripDomainTag(topic)
        if (lemmatizedPreferred.any { it.equals(base, ignoreCase = true) }) return true

        val evidence = brain.topicEvidence[base] ?: brain.topicEvidence[topic]
        return evidence != null &&
            (
                evidence.explicitSignals > 0 ||
                    evidence.watchSignals >= 2 ||
                    evidence.videoIds.size >= 2 ||
                    evidence.positiveScore >= 1.2
            )
    }

    fun calculateTopicProbationPenalty(
        videoVector: ContentVector,
        brain: UserBrain,
        lemmatizedPreferred: Set<String>,
    ): Double {
        if (brain.totalInteractions < COLD_START_THRESHOLD) return 1.0

        val topTopics =
            videoVector.topics.entries
                .sortedByDescending { it.value }
                .take(4)
                .map { stripDomainTag(it.key) }
                .filter(NeuroText::isTopicSized)
                .distinct()

        if (topTopics.isEmpty()) return 1.0

        val probationaryCount =
            topTopics.count { topic ->
                val score = topicScore(brain, topic)
                score in 0.015..0.20 && !hasConfirmedTopicEvidence(topic, brain, lemmatizedPreferred)
            }

        if (probationaryCount == 0) return 1.0

        val ratio = probationaryCount.toDouble() / topTopics.size.toDouble()
        if (ratio <= 0.5) return 1.0
        return (1.0 - ratio * 0.20).coerceIn(0.75, 1.0)
    }

    /**
     * Beta-posterior exploration bonus. Models per-topic appeal as Beta(1+pos, 1+neg);
     * the posterior std is the exploration value — high when evidence is thin, low once
     * a topic is clearly liked OR disliked. Deterministic (no sampling), persona-weighted
     * by the caller and bounded, so focused users stay mostly exploit and repeatedly
     * rejected topics are not re-surfaced as "exploration".
     */
    fun explorationBonus(
        videoVector: ContentVector,
        brain: UserBrain,
        exploreWeight: Double,
    ): Double {
        if (exploreWeight <= 0.0 || brain.totalInteractions < COLD_START_THRESHOLD) return 0.0
        val primary =
            videoVector.topics
                .maxByOrNull { it.value }
                ?.key
                ?.let { stripDomainTag(it) } ?: return 0.0
        val ev = brain.topicEvidence[primary]
        val alpha = 1.0 + (ev?.positiveSignals ?: 0)
        val beta = 1.0 + (ev?.negativeSignals ?: 0)
        val total = alpha + beta
        val std = sqrt(alpha * beta / (total * total * (total + 1.0)))
        return (EXPLORE_BETA_C * std * exploreWeight).coerceAtMost(EXPLORE_MAX_BONUS)
    }

    /** Boost for the candidate's topic pairs the viewer watches together, capped per video. */
    fun affinityBoost(
        videoVector: ContentVector,
        p: ScoringParams,
    ): Double {
        val videoTopics =
            videoVector.topics.keys
                .map { stripDomainTag(it) }
                .distinct()
        var boost = 0.0
        val neighbours = p.affinityNeighbours
        for (i in videoTopics.indices) {
            val ofTopic = neighbours[videoTopics[i]] ?: continue
            for (j in i + 1 until videoTopics.size) {
                val affinity = ofTopic[videoTopics[j]] ?: continue
                boost += affinity * AFFINITY_BOOST_PER_PAIR
            }
        }
        return boost.coerceAtMost(AFFINITY_MAX_BOOST_PER_VIDEO)
    }

    /**
     * Deterministic per-candidate score: the full factor pipeline minus the
     * exploration jitter (which stays in the caller so this stays pure).
     * Combination order and add/multiply semantics are unchanged.
     */
    fun scoreCandidate(
        video: Video,
        videoVector: ContentVector,
        p: ScoringParams,
    ): Double {
        val brain = p.brain

        val personalityScore =
            if (video.isShort && brain.shortsVector.topics.isNotEmpty()) {
                val globalSim = NeuroVectorMath.calculateCosineSimilarity(p.preparedTaste, videoVector)
                val shortsSim = NeuroVectorMath.calculateCosineSimilarity(p.preparedShorts, videoVector)
                globalSim * 0.4 + shortsSim * 0.6
            } else {
                NeuroVectorMath.calculateCosineSimilarity(p.preparedTaste, videoVector)
            }
        val contextScore = NeuroVectorMath.calculateCosineSimilarity(p.preparedContext, videoVector)
        // Smooth ramp instead of a cliff at the gate: two near-identical candidates
        // straddling the threshold no longer get wildly different novelty credit.
        val noveltyScore =
            if (p.isColdStart) {
                1.0 - personalityScore
            } else {
                smoothstep(NOVELTY_RELEVANCE_GATE, NOVELTY_RELEVANCE_GATE + 0.1, personalityScore) *
                    (1.0 - personalityScore)
            }

        var totalScore =
            (personalityScore * p.wPersonality) +
                (contextScore * p.wContext) +
                (noveltyScore * p.wNovelty)

        totalScore *= calculateTopicProbationPenalty(videoVector, brain, p.lemmatizedPreferred)

        if (brain.topicAffinities.isNotEmpty()) totalScore += affinityBoost(videoVector, p)

        totalScore += NeuroFormats.scoreTerm(brain.formatAffinity, videoVector.formats)

        totalScore += calculateChannelSignal(video, brain, p.userSubs)

        totalScore += (p.coWatchPrior[video.channelId] ?: 0.0) * FlowNeuroEngine.COWATCH_MAX_PRIOR

        totalScore += calculateSerendipity(noveltyScore, contextScore)

        if (p.isColdStart && video.viewCount > 0) {
            val popularityBoost = log10(1.0 + video.viewCount.toDouble()) / 10.0 * 0.05
            totalScore += popularityBoost
        }

        totalScore *= calculateEngagementQuality(video, p.isColdStart)

        val ageMultiplier = TimeDecay.calculateMultiplier(video.uploadDate, video.isLive)
        val isClassic = isVideoClassic(video.viewCount)
        val isSub = p.userSubs.contains(video.channelId)
        val finalAgeFactor =
            when {
                isClassic || isSub -> (ageMultiplier + 1.0) / 2.0
                else -> ageMultiplier
            }
        totalScore *= finalAgeFactor

        totalScore *= categoryFactor(video, videoVector, p, isSub)

        totalScore +=
            calculateCuriosityBonus(
                personalityScore,
                brain.globalVector.complexity,
                videoVector.complexity,
            )

        totalScore *=
            calculateFreshness(
                video,
                videoVector,
                personalityScore,
                p.sessionTopics,
                p.sessionVideoCount,
                p.impressions[video.id],
                p.now,
            )

        if (p.isOnboarding) {
            val hasPreferred = p.lemmatizedPreferred.any { videoVector.topics.containsKey(it) }
            if (hasPreferred) {
                totalScore += p.onboardingWarmup * ONBOARDING_MAX_BOOST
            }
        }

        totalScore *= calculateWatchedPenalty(video, p.watchHistory[video.id])

        totalScore *= calculateAntiRecommendationPenalty(videoVector, video, brain)

        totalScore *= calculateRejectionPatternPenalty(videoVector, brain.rejectionPatterns, p.now)

        totalScore *= calculateRelevanceFloor(personalityScore, brain.totalInteractions, isSub)

        totalScore *=
            calculateFeedHistoryPenalty(
                video.id,
                brain.feedHistory,
                p.now,
                p.candidatePoolSize,
            )

        totalScore *=
            calculateImplicitDisinterestPenalty(
                video.id,
                brain.feedHistory,
                p.watchHistory,
                p.now,
            )

        totalScore += calculateMomentumBoost(videoVector, p.recentInteractions, personalityScore)

        totalScore += explorationBonus(videoVector, brain, p.exploreWeight)

        if (video.isShort) {
            val seenTimestamp = brain.seenShortsHistory[video.id]
            if (seenTimestamp != null) {
                val daysSinceSeen = (p.now - seenTimestamp) / (24.0 * 60 * 60 * 1000)
                if (daysSinceSeen < SEEN_SHORT_EXPIRY_DAYS) {
                    val recovery = (daysSinceSeen / SEEN_SHORT_EXPIRY_DAYS).coerceIn(0.0, 1.0)
                    val seenPenalty = SEEN_SHORT_PENALTY + (1.0 - SEEN_SHORT_PENALTY) * recovery
                    totalScore *= seenPenalty
                }
            }
        }

        return totalScore
    }

    /** A soft category mismatch ([NeuroCategories]); subscriptions and picked topics are exempt. */
    private fun categoryFactor(
        video: Video,
        videoVector: ContentVector,
        p: ScoringParams,
        isSub: Boolean,
    ): Double {
        if (isSub || p.lemmatizedPreferred.any { it in videoVector.topics }) return 1.0
        val category =
            NeuroCategories.idOf(video.category)?.let { ChannelCategory(it, 1.0) }
                ?: p.categoryOf(video.channelId)
                ?: return 1.0
        return NeuroCategories.factor(p.brain.categoryAffinity, category)
    }

    /**
     * Smart diversity re-ranking across channels and topics. With [dropUnrelated] a video [isUnrelated]
     * calls unrelated is left out; otherwise it waits for the relaxed fill.
     */
    fun applySmartDiversity(
        candidates: MutableList<ScoredVideo>,
        tokenizer: NeuroTokenizer,
        extraExplorationSlots: Int = 0,
        isUnrelated: (ScoredVideo) -> Boolean = { false },
        trace: RankTrace? = null,
        dropUnrelated: Boolean = false,
    ): List<Video> {
        fun placed(
            scored: ScoredVideo,
            phase: DiversityPhase,
        ) {
            if (trace == null) return
            trace.phases[scored.video.id] = phase
            if (isUnrelated(scored)) trace.unrelated += scored.video.id
        }
        val finalPlaylist = mutableListOf<Video>()
        val channelWindow = mutableListOf<String>()
        val topicWindow = mutableListOf<String>()
        val tokenCache = HashMap<String, Set<String>>(candidates.size * 2)

        candidates.sortByDescending { it.score }

        val uniqueTopics =
            candidates
                .mapNotNull {
                    it.vector.topics
                        .maxByOrNull { e -> e.value }
                        ?.key
                        ?.let { k -> stripDomainTag(k) }
                }.distinct()
        val topicDiversity = uniqueTopics.size

        val maxPerTopic =
            when {
                topicDiversity <= 2 -> 6
                topicDiversity <= 4 -> 4
                topicDiversity <= 7 -> 3
                else -> 3
            }

        val explorationSlots =
            when {
                topicDiversity <= 2 -> 2
                topicDiversity <= 4 -> 2
                else -> 1
            } + extraExplorationSlots

        val userTopTopics =
            candidates
                .flatMap { it.vector.topics.entries }
                .groupBy { stripDomainTag(it.key) }
                .mapValues { (_, entries) -> entries.sumOf { it.value } }
                .entries
                .sortedByDescending { it.value }
                .take(3)
                .map { it.key }
                .toSet()

        // Phase 1: Strict diversity
        val deferredHighQuality = mutableListOf<ScoredVideo>()
        val phase1Candidates = candidates.toMutableList()
        val phase1Iterator = phase1Candidates.iterator()
        var explorationCount = 0
        val topScore = candidates.firstOrNull()?.score ?: 0.0

        while (phase1Iterator.hasNext() &&
            finalPlaylist.size < DIVERSITY_PHASE1_TARGET
        ) {
            val current = phase1Iterator.next()
            val primaryTopic =
                current.vector.topics
                    .maxByOrNull { it.value }
                    ?.key
                    ?.let { stripDomainTag(it) } ?: ""

            val channelCount =
                channelWindow
                    .count { it == current.video.channelId }
            val topicCount = topicWindow.count { it == primaryTopic }

            val isTitleSimilar =
                finalPlaylist
                    .takeLast(5)
                    .any { existing ->
                        val tokens1 = tokenCache.getOrPut(current.video.title) { tokenizer.tokenizeForSimilarity(current.video.title) }
                        val tokens2 = tokenCache.getOrPut(existing.title) { tokenizer.tokenizeForSimilarity(existing.title) }
                        NeuroVectorMath.calculateTitleSimilarity(tokens1, tokens2) >
                            TITLE_SIMILARITY_STRICT
                    }

            val isNovelTopic =
                primaryTopic.isNotEmpty() &&
                    !userTopTopics.contains(primaryTopic)

            // Novel topics must score above a minimum to qualify for exploration slots
            val qualifiesForExploration =
                isNovelTopic &&
                    explorationCount < explorationSlots &&
                    topScore > 0 &&
                    current.score >= topScore * EXPLORATION_MIN_SCORE_RATIO

            val effectiveTopicCap =
                if (qualifiesForExploration) {
                    maxPerTopic + 1
                } else {
                    maxPerTopic
                }

            // A slot among the first is never a prize for being from a new channel alone: a video that
            // shares nothing with the viewer waits for the relaxed fill, after the ones that do (#907).
            if (channelCount == 0 &&
                !isUnrelated(current) &&
                topicCount < effectiveTopicCap &&
                !isTitleSimilar
            ) {
                finalPlaylist.add(current.video)
                channelWindow.add(current.video.channelId)
                if (primaryTopic.isNotEmpty()) {
                    topicWindow.add(primaryTopic)
                }
                if (qualifiesForExploration) explorationCount++
                placed(current, if (qualifiesForExploration) DiversityPhase.EXPLORATION else DiversityPhase.STRICT)
                phase1Iterator.remove()
            } else if (topScore > 0 &&
                current.score > (topScore * 0.8)
            ) {
                deferredHighQuality.add(current)
                phase1Iterator.remove()
            }
        }

        // Phase 2: Deferred quality
        deferredHighQuality.sortByDescending { it.score }
        for (scored in deferredHighQuality) {
            val recentChannels =
                finalPlaylist
                    .takeLast(7)
                    .map { it.channelId }
            val channelOk =
                recentChannels
                    .count { it == scored.video.channelId } < 2
            val titleOk =
                finalPlaylist
                    .takeLast(5)
                    .none { existing ->
                        val tokens1 = tokenCache.getOrPut(scored.video.title) { tokenizer.tokenizeForSimilarity(scored.video.title) }
                        val tokens2 = tokenCache.getOrPut(existing.title) { tokenizer.tokenizeForSimilarity(existing.title) }
                        NeuroVectorMath.calculateTitleSimilarity(tokens1, tokens2) >
                            TITLE_SIMILARITY_RELAXED
                    }
            if (channelOk && titleOk && !(dropUnrelated && isUnrelated(scored))) {
                finalPlaylist.add(scored.video)
                placed(scored, DiversityPhase.DEFERRED)
            }
        }

        // Phase 3: Relaxed fill
        phase1Candidates.sortByDescending { it.score }
        for (scored in phase1Candidates) {
            val recentChannels =
                finalPlaylist
                    .takeLast(5)
                    .map { it.channelId }
            val channelSpam =
                recentChannels
                    .count { it == scored.video.channelId } >= 2
            val titleSimilar =
                finalPlaylist
                    .takeLast(5)
                    .any { existing ->
                        val tokens1 = tokenCache.getOrPut(scored.video.title) { tokenizer.tokenizeForSimilarity(scored.video.title) }
                        val tokens2 = tokenCache.getOrPut(existing.title) { tokenizer.tokenizeForSimilarity(existing.title) }
                        NeuroVectorMath.calculateTitleSimilarity(tokens1, tokens2) >
                            TITLE_SIMILARITY_RELAXED
                    }
            if (!channelSpam && !titleSimilar && !(dropUnrelated && isUnrelated(scored))) {
                finalPlaylist.add(scored.video)
                placed(scored, DiversityPhase.RELAXED)
            }
        }

        return finalPlaylist
    }
}
