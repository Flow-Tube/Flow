/*
 * Copyright (C) 2025-2026 Flow | A-EDev
 *
 * This file is part of Flow (https://github.com/Flow-Tube/Flow).
 * Test-source-set only — never shipped in the APK.
 */

package io.github.aedev.flow.data.recommendation.eval

import io.github.aedev.flow.data.recommendation.ContentVector
import io.github.aedev.flow.data.recommendation.FeedEntry
import io.github.aedev.flow.data.recommendation.FeedImpressionLedger
import io.github.aedev.flow.data.recommendation.FlowPersona
import io.github.aedev.flow.data.recommendation.NeuroDiscovery
import io.github.aedev.flow.data.recommendation.NeuroScoring
import io.github.aedev.flow.data.recommendation.NeuroTokenizer
import io.github.aedev.flow.data.recommendation.NeuroTopicCatalog
import io.github.aedev.flow.data.recommendation.ScoredVideo
import io.github.aedev.flow.data.recommendation.TopicEvidence
import io.github.aedev.flow.data.recommendation.UserBrain
import io.github.aedev.flow.data.recommendation.WatchEntry
import java.util.Random

/**
 * End-to-end offline benchmark for the recommendation pipeline.
 *
 * Simulates a multi-interest user across N home-feed refreshes, driving the REAL
 * production path (NeuroDiscovery.generateQueries → NeuroScoring.scoreCandidate →
 * applySmartDiversity) against a finite candidate universe, with impressions and
 * watches fed back between refreshes exactly as the engine records them.
 *
 * Finite per-group candidate catalogs model the reality that repeating the same
 * search query returns largely the same results — the repetition pressure the
 * engine must fight.
 *
 * Metrics answer the two product questions directly:
 *  - repeatRate@K: how much of each refresh the user has already been shown
 *  - groupCoverage / weakGroupServiceRate: do ALL saved interests reach the feed,
 *    or do the top interests monopolize it
 */
internal object NeuroBenchmark {
    private const val REFRESH_INTERVAL_MS = 4L * 60 * 60 * 1000

    // ── Universe ──

    data class InterestGroup(
        val name: String,
        val topics: List<String>,
        val strength: Double,
        val channels: List<String>,
    )

    data class Universe(
        val groups: List<InterestGroup>,
        val noiseTopics: List<String>,
        val catalogPerGroup: Int,
    )

    /**
     * Six interests of graded strength — the "diverse user" the product promises
     * to serve. Weak-tail groups (physics, chess, woodworking) are the canary:
     * a healthy engine cycles them into the feed; a looping engine never does.
     */
    fun multiInterestUniverse(): Universe =
        Universe(
            groups =
                listOf(
                    InterestGroup("gaming", listOf("minecraft", "speedrun", "roblox"), 0.65, listOf("chG1", "chG2", "chG3")),
                    InterestGroup("cooking", listOf("cooking", "recipe", "baking"), 0.45, listOf("chC1", "chC2", "chC3")),
                    InterestGroup("guitar", listOf("guitar", "chord", "acoustic"), 0.40, listOf("chU1", "chU2", "chU3")),
                    InterestGroup("physics", listOf("physics", "quantum", "astronomy"), 0.25, listOf("chP1", "chP2")),
                    InterestGroup("chess", listOf("chess", "opening", "endgame"), 0.18, listOf("chS1", "chS2")),
                    InterestGroup("woodworking", listOf("woodworking", "joinery", "sawmill"), 0.12, listOf("chW1", "chW2")),
                ),
            noiseTopics = listOf("celebrity", "gossip", "prank", "unboxing", "lottery"),
            catalogPerGroup = 50,
        )

    fun brainFor(universe: Universe): UserBrain {
        val topics = mutableMapOf<String, Double>()
        val affinities = mutableMapOf<String, Double>()
        val evidence = mutableMapOf<String, TopicEvidence>()
        val channelScores = mutableMapOf<String, Double>()

        universe.groups.forEach { group ->
            group.topics.forEachIndexed { i, topic ->
                val scale =
                    when (i) {
                        0 -> 1.0
                        1 -> 0.75
                        else -> 0.55
                    }
                topics[topic] = group.strength * scale
            }
            for (i in group.topics.indices) {
                for (j in i + 1 until group.topics.size) {
                    affinities[NeuroScoring.makeAffinityKey(group.topics[i], group.topics[j])] = 0.20
                }
            }
            // Strong interests carry confirmed evidence; the weak tail stays thin —
            // realistic for interests picked up from a handful of watches.
            if (group.strength >= 0.40) {
                evidence[group.topics[0]] =
                    TopicEvidence(
                        positiveSignals = 6,
                        watchSignals = 3,
                        explicitSignals = 1,
                        positiveScore = 3.0,
                        videoIds = setOf("${group.name}#seed1", "${group.name}#seed2"),
                        firstSeenAt = 1L,
                        lastSeenAt = NeuroEval.FIXED_NOW,
                    )
            } else {
                evidence[group.topics[0]] =
                    TopicEvidence(
                        positiveSignals = 2,
                        watchSignals = 1,
                        positiveScore = 0.8,
                        videoIds = setOf("${group.name}#seed1"),
                        firstSeenAt = 1L,
                        lastSeenAt = NeuroEval.FIXED_NOW,
                    )
            }
            group.channels.forEach { ch -> channelScores[ch] = if (group.strength >= 0.40) 0.60 else 0.50 }
        }

        return UserBrain(
            globalVector = ContentVector(topics = topics),
            topicAffinities = affinities,
            topicEvidence = evidence,
            channelScores = channelScores,
            preferredTopics =
                universe.groups
                    .filter { it.strength >= 0.40 }
                    .map { it.topics[0] }
                    .toSet(),
            totalInteractions = 200,
            hasCompletedOnboarding = true,
        )
    }

    // ── Candidate catalog ──

    class Catalog(
        private val universe: Universe,
        seed: Long,
    ) {
        private val rng = Random(seed)
        private val byGroup: Map<String, List<NeuroEval.Labeled>> =
            universe.groups.associate { group -> group.name to (0 until universe.catalogPerGroup).map { i -> item(group, i) } }
        private val noise: List<NeuroEval.Labeled> =
            (0 until 30).map { i ->
                val topic = universe.noiseTopics[i % universe.noiseTopics.size]
                NeuroEval.Labeled(
                    NeuroEval.video(id = "noise#$i", title = "$topic clip $i", channelId = "chN${i % 5}"),
                    NeuroEval.vec(topic to 0.9),
                    relevance = 0.0,
                )
            }

        private fun item(
            group: InterestGroup,
            i: Int,
        ): NeuroEval.Labeled {
            val primary = group.topics[i % group.topics.size]
            val secondary = group.topics[(i + 1) % group.topics.size]
            return NeuroEval.Labeled(
                NeuroEval.video(
                    id = "${group.name}#$i",
                    title = "$primary $secondary session $i",
                    channelId = group.channels[i % group.channels.size],
                ),
                NeuroEval.vec(
                    primary to 0.70 + (i % 5) * 0.04,
                    secondary to 0.35,
                ),
                relevance = group.strength,
            )
        }

        fun sampleGroup(
            groupName: String,
            n: Int,
        ): List<NeuroEval.Labeled> {
            val catalog = byGroup[groupName] ?: return emptyList()
            // Queries surface a stable head plus a rotating tail — like real search.
            val head = catalog.take(n / 2)
            val tail = (0 until n - head.size).map { catalog[rng.nextInt(catalog.size)] }
            return head + tail
        }

        fun sampleNoise(n: Int): List<NeuroEval.Labeled> = (0 until n).map { noise[rng.nextInt(noise.size)] }
    }

    // ── Metrics ──

    data class RefreshMetrics(
        val refresh: Int,
        /** Share of this refresh the user had ALREADY SEEN (impressed) before — the UX repeat. */
        val seenRepeatRate: Double,
        /** Share previously served anywhere (incl. below the fold the user never saw). */
        val servedRepeatRate: Double,
        val groupCoverage: Double,
        val ndcg: Double,
        val ild: Double,
        val concentration: Double,
        val servedGroups: Map<String, Int>,
    )

    data class ServingSummary(
        val meanSeenRepeatRate: Double,
        val meanServedRepeatRate: Double,
        val uniqueServedRatio: Double,
        val meanGroupCoverage: Double,
        val cumulativeGroupCoverage: Double,
        val weakGroupServiceRate: Double,
        val meanNdcg: Double,
        val meanIld: Double,
        val meanConcentration: Double,
    )

    data class ServingResult(
        val perRefresh: List<RefreshMetrics>,
        val summary: ServingSummary,
        val queriesPerRefresh: List<List<String>>,
    )

    private fun groupOf(id: String): String = id.substringBefore('#')

    private fun herfindahl(counts: Collection<Int>): Double {
        val total = counts.sum().toDouble()
        if (total <= 0) return 0.0
        return counts.sumOf { (it / total) * (it / total) }
    }

    // ── Serving simulation ──

    fun simulateServing(
        universe: Universe = multiInterestUniverse(),
        refreshes: Int = 8,
        topK: Int = 20,
        viewportK: Int = 12,
        perQueryCandidates: Int = 12,
        noisePerRefresh: Int = 6,
        seed: Long = 42L,
        seenGateEnabled: Boolean = true,
    ): ServingResult {
        val tokenizer = NeuroTokenizer()
        val discovery = NeuroDiscovery(NeuroTopicCatalog.TOPIC_CATEGORIES, tokenizer)
        val catalog = Catalog(universe, seed)
        var brain = brainFor(universe)
        val userSubs =
            setOf(
                universe.groups
                    .first()
                    .channels
                    .first(),
            )
        val preferredLemmas = brain.preferredTopics.map { tokenizer.normalizeLemma(it) }.toSet()

        val feedHistory = mutableMapOf<String, FeedEntry>()
        val watchHistory = mutableMapOf<String, WatchEntry>()
        val servedSoFar = mutableSetOf<String>()
        val impressedSoFar = mutableSetOf<String>()
        val weakGroups =
            universe.groups
                .filter { it.strength < 0.30 }
                .map { it.name }
                .toSet()
        val servedGroupsCumulative = mutableSetOf<String>()

        val perRefresh = mutableListOf<RefreshMetrics>()
        val queriesPerRefresh = mutableListOf<List<String>>()
        var clusterRotation = mapOf<String, Long>()
        var now = NeuroEval.FIXED_NOW

        repeat(refreshes) { r ->
            // 1. Discovery decides what to search for (the real production selector),
            //    with cluster rotation advanced between refreshes as the engine does.
            val discoveryBrain = brain.copy(clusterRotation = clusterRotation)
            val discoveryQueries = discovery.generateQueries(discoveryBrain, now) { FlowPersona.EXPLORER }.take(8)
            val queries =
                discoveryQueries.map { it.query }.ifEmpty { universe.groups.take(4).map { it.topics[0] } }
            queriesPerRefresh += queries
            val servedClusters = discoveryQueries.mapNotNull { it.clusterKey }.toSet()
            if (servedClusters.isNotEmpty()) {
                clusterRotation = clusterRotation + servedClusters.associateWith { now }
            }

            // 2. Each query pulls from the finite catalog of whatever group it targets.
            val pool = LinkedHashMap<String, NeuroEval.Labeled>()
            queries.forEach { q ->
                val tokens = tokenizer.tokenize(q).map { tokenizer.normalizeLemma(it) }.toSet()
                val group = universe.groups.firstOrNull { g -> g.topics.any { tokenizer.normalizeLemma(it) in tokens } }
                val sampled =
                    if (group != null) catalog.sampleGroup(group.name, perQueryCandidates) else catalog.sampleNoise(perQueryCandidates / 2)
                sampled.forEach { pool.putIfAbsent(it.video.id, it) }
            }
            catalog.sampleNoise(noisePerRefresh).forEach { pool.putIfAbsent(it.video.id, it) }

            // 3. Score + diversity re-rank through the production pipeline (no jitter).
            // Mirrors rank(): the hard seen-gate runs before scoring.
            val brainNow = brain.copy(feedHistory = feedHistory.toMap())
            val gatedPool =
                if (seenGateEnabled) {
                    NeuroScoring.applySeenGate(pool.values.toList(), brainNow.feedHistory, now) { it.video.id }
                } else {
                    pool.values.toList()
                }
            val params =
                NeuroEval.params(
                    brain = brainNow,
                    userSubs = userSubs,
                    preferredLemmas = preferredLemmas,
                    poolSize = gatedPool.size,
                    watchHistory = watchHistory.toMap(),
                    now = now,
                )
            val scored =
                gatedPool
                    .map { ScoredVideo(it.video, NeuroScoring.scoreCandidate(it.video, it.vector, params), it.vector) }
                    .toMutableList()
            val feed = NeuroScoring.applySmartDiversity(scored, tokenizer).take(topK)
            val feedLabeled = feed.mapNotNull { v -> pool[v.id] }

            // 4. Metrics for this refresh.
            val feedIds = feed.map { it.id }
            val seenRepeats = feedIds.count { it in impressedSoFar }
            val servedRepeats = feedIds.count { it in servedSoFar }
            val groupsInFeed = feedIds.map { groupOf(it) }.filter { g -> universe.groups.any { it.name == g } }
            val servedGroupCounts = groupsInFeed.groupingBy { it }.eachCount()
            servedGroupsCumulative += servedGroupCounts.keys
            perRefresh +=
                RefreshMetrics(
                    refresh = r + 1,
                    seenRepeatRate = if (r == 0) 0.0 else seenRepeats.toDouble() / feedIds.size,
                    servedRepeatRate = if (r == 0) 0.0 else servedRepeats.toDouble() / feedIds.size,
                    groupCoverage = servedGroupCounts.size.toDouble() / universe.groups.size,
                    ndcg = NeuroEval.ndcg(feedLabeled, feedIds.size),
                    ild = NeuroEval.ild(feedLabeled, feedIds.size),
                    concentration = herfindahl(servedGroupCounts.values),
                    servedGroups = servedGroupCounts,
                )
            servedSoFar += feedIds
            impressedSoFar += feedIds.take(viewportK)

            // 5. Feedback: viewport impressions, counted as the engine counts them, + one watch from a
            // strong interest.
            FeedImpressionLedger.countable(feedIds.take(viewportK), feedHistory, now).forEach { id ->
                val prev = feedHistory[id]
                feedHistory[id] = FeedEntry(lastShown = now, showCount = (prev?.showCount ?: 0) + 1)
            }
            feedIds
                .firstOrNull { id ->
                    universe.groups
                        .firstOrNull { it.name == groupOf(id) }
                        ?.strength
                        ?.let { it >= 0.40 } == true &&
                        id !in watchHistory
                }?.let { watchedId -> watchHistory[watchedId] = WatchEntry(0.9f, now) }

            now += REFRESH_INTERVAL_MS
        }

        val summary =
            ServingSummary(
                meanSeenRepeatRate = perRefresh.drop(1).map { it.seenRepeatRate }.averageOrZero(),
                meanServedRepeatRate = perRefresh.drop(1).map { it.servedRepeatRate }.averageOrZero(),
                uniqueServedRatio = servedSoFar.size.toDouble() / (refreshes * topK),
                meanGroupCoverage = perRefresh.map { it.groupCoverage }.averageOrZero(),
                cumulativeGroupCoverage = servedGroupsCumulative.size.toDouble() / universe.groups.size,
                weakGroupServiceRate =
                    weakGroups.count { it in servedGroupsCumulative }.toDouble() / weakGroups.size.coerceAtLeast(1),
                meanNdcg = perRefresh.map { it.ndcg }.averageOrZero(),
                meanIld = perRefresh.map { it.ild }.averageOrZero(),
                meanConcentration = perRefresh.map { it.concentration }.averageOrZero(),
            )
        return ServingResult(perRefresh, summary, queriesPerRefresh)
    }

    // ── Discovery coverage benchmark ──

    data class DiscoveryResult(
        val sessions: List<List<String>>,
        val perSessionGroupCoverage: List<Double>,
        val cumulativeGroupCoverage: Double,
        val meanSessionOverlap: Double,
    )

    /**
     * Runs the query selector across simulated sessions and measures which of the
     * user's interest groups its queries ever target, plus session-over-session
     * query overlap. This is the direct measurement of "stuck on top topics".
     */
    fun discoveryCoverage(
        universe: Universe = multiInterestUniverse(),
        sessions: Int = 6,
    ): DiscoveryResult {
        val tokenizer = NeuroTokenizer()
        val discovery = NeuroDiscovery(NeuroTopicCatalog.TOPIC_CATEGORIES, tokenizer)
        val brain = brainFor(universe)

        val sessionQueries = mutableListOf<List<String>>()
        val perSessionCoverage = mutableListOf<Double>()
        val cumulativeGroups = mutableSetOf<String>()
        val overlaps = mutableListOf<Double>()
        var clusterRotation = mapOf<String, Long>()
        var now = NeuroEval.FIXED_NOW

        repeat(sessions) { round ->
            val discoveryBrain = brain.copy(clusterRotation = clusterRotation)
            // Round index doubles as tree depth — models refresh + successive pages.
            val sessionResult = discovery.generateQueries(discoveryBrain, now, depth = round) { FlowPersona.EXPLORER }
            val queries = sessionResult.map { it.query }
            val servedClusters = sessionResult.mapNotNull { it.clusterKey }.toSet()
            if (servedClusters.isNotEmpty()) {
                clusterRotation = clusterRotation + servedClusters.associateWith { now }
            }
            now += REFRESH_INTERVAL_MS
            val groups =
                queries
                    .mapNotNull { q ->
                        val tokens = tokenizer.tokenize(q).map { tokenizer.normalizeLemma(it) }.toSet()
                        universe.groups.firstOrNull { g -> g.topics.any { tokenizer.normalizeLemma(it) in tokens } }?.name
                    }.toSet()
            perSessionCoverage += groups.size.toDouble() / universe.groups.size
            cumulativeGroups += groups
            sessionQueries.lastOrNull()?.let { prev ->
                val a = prev.toSet()
                val b = queries.toSet()
                if (a.isNotEmpty() || b.isNotEmpty()) {
                    overlaps += a.intersect(b).size.toDouble() / a.union(b).size
                }
            }
            sessionQueries += queries
        }

        return DiscoveryResult(
            sessions = sessionQueries,
            perSessionGroupCoverage = perSessionCoverage,
            cumulativeGroupCoverage = cumulativeGroups.size.toDouble() / universe.groups.size,
            meanSessionOverlap = overlaps.averageOrZero(),
        )
    }

    // ── Report rendering ──

    fun renderReport(
        label: String,
        serving: ServingResult,
        discoveryResult: DiscoveryResult,
    ): String =
        buildString {
            appendLine("═══ NeuroBenchmark [$label] ═══")
            appendLine()
            appendLine("SERVING (8 refreshes, top-20, finite catalogs)")
            appendLine("  refresh | seenRep | servedRep | coverage | ndcg  | ild   | conc  | groups")
            serving.perRefresh.forEach { m ->
                appendLine(
                    "  %7d | %7.2f | %9.2f | %8.2f | %5.3f | %5.3f | %5.3f | %s".format(
                        m.refresh,
                        m.seenRepeatRate,
                        m.servedRepeatRate,
                        m.groupCoverage,
                        m.ndcg,
                        m.ild,
                        m.concentration,
                        m.servedGroups,
                    ),
                )
            }
            val s = serving.summary
            appendLine()
            appendLine("  meanSeenRepeatRate (r2+) = %.3f".format(s.meanSeenRepeatRate))
            appendLine("  meanServedRepeatRate     = %.3f".format(s.meanServedRepeatRate))
            appendLine("  uniqueServedRatio        = %.3f".format(s.uniqueServedRatio))
            appendLine("  meanGroupCoverage        = %.3f".format(s.meanGroupCoverage))
            appendLine("  cumulativeGroupCoverage  = %.3f".format(s.cumulativeGroupCoverage))
            appendLine("  weakGroupServiceRate     = %.3f".format(s.weakGroupServiceRate))
            appendLine("  meanNdcg                 = %.3f".format(s.meanNdcg))
            appendLine("  meanIld                  = %.3f".format(s.meanIld))
            appendLine("  meanConcentration        = %.3f".format(s.meanConcentration))
            appendLine()
            appendLine("DISCOVERY (6 sessions)")
            discoveryResult.sessions.forEachIndexed { i, q -> appendLine("  s${i + 1}: $q") }
            appendLine("  perSessionGroupCoverage  = ${discoveryResult.perSessionGroupCoverage.map { "%.2f".format(it) }}")
            appendLine("  cumulativeGroupCoverage  = %.3f".format(discoveryResult.cumulativeGroupCoverage))
            appendLine("  meanSessionOverlap       = %.3f".format(discoveryResult.meanSessionOverlap))
        }

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()
}
