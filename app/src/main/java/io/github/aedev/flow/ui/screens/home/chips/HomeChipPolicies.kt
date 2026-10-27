package io.github.aedev.flow.ui.screens.home.chips

import io.github.aedev.flow.data.local.VideoHistoryEntry
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.recommendation.InterestChip
import io.github.aedev.flow.data.recommendation.NeuroScoring

/** Tuning for the Home chips, in one place. */
internal object HomeChipParams {
    const val MAX_VIDEOS = 40
    const val QUERIES_PER_INTEREST = 3
    const val SEARCH_TIMEOUT_MS = 6_000L
    const val RELATED_SEEDS = 2
    const val HISTORY_LOOKBACK = 400
    const val RECENT_WINDOW_MS = 7L * 24L * 60L * 60L * 1000L
    const val WATCHED_SKIP_RECENT_MS = 24L * 60L * 60L * 1000L
    const val WATCHED_MIN_VIDEOS = 4
    const val MIXES_MIN = 4
    const val MIXES_MAX = 6
    const val MIX_LENGTH = 25
    const val MIX_MIN_VIDEOS = 5
    const val SESSION_TOPICS = 5
    const val LIVE_FALLBACK_QUERY = "live"
}

/** The order chips appear in, which never changes between loads. Empty chips are left out. */
internal fun visibleChips(
    interests: List<InterestChip>,
    emptyKeys: Set<String>,
    hasMixSeeds: Boolean,
    hasWatched: Boolean,
    selected: String,
): List<HomeChip> {
    val all =
        listOf(HomeChip.All) +
            interests.map(HomeChip::Interest) +
            listOf(HomeChip.NewToYou, HomeChip.RecentlyUploaded, HomeChip.Mixes, HomeChip.Live, HomeChip.Watched)
    return all.filter { chip ->
        when {
            chip.key == selected || chip == HomeChip.All -> true
            chip.key in emptyKeys -> false
            chip == HomeChip.Mixes -> hasMixSeeds
            chip == HomeChip.Watched -> hasWatched
            else -> true
        }
    }
}

/**
 * Searches for an interest chip: its own phrases first, a lone word qualified by one of them. A theme
 * chip searches each of its interests in turn.
 */
internal fun interestQueries(
    interest: InterestChip,
    max: Int = HomeChipParams.QUERIES_PER_INTEREST,
): List<String> {
    if (interest.parts.isNotEmpty()) {
        val perPart = interest.parts.map { interestQueries(it, max) }
        return (0 until max)
            .flatMap { round -> perPart.mapNotNull { it.getOrNull(round) } }
            .distinct()
            .take(max)
    }
    val rep = NeuroScoring.stripDomainTag(interest.representative)
    val members = interest.topics.map(NeuroScoring::stripDomainTag).filter { it != rep }

    fun qualified(member: String) = if (rep in member.split(' ')) member else "$rep $member"
    val queries = LinkedHashSet<String>()
    if (' ' in rep) queries += rep
    members.filter { ' ' in it }.forEach { queries += qualified(it) }
    members.filter { ' ' !in it }.forEach { queries += qualified(it) }
    if (queries.isEmpty()) queries += rep
    return queries.take(max)
}

/** Top-interest live queries; generic fallback when none. */
internal fun liveQueries(interests: List<InterestChip>): List<String> =
    interests
        .take(2)
        .mapNotNull { interestQueries(it, 1).firstOrNull() }
        .ifEmpty { listOf(HomeChipParams.LIVE_FALLBACK_QUERY) }

/** Only channels the viewer has never watched, followed or rejected, one video each. */
internal fun fromUnknownChannels(
    videos: List<Video>,
    knownChannels: Set<String>,
): List<Video> =
    videos
        .filter { it.channelId.isNotBlank() && it.channelId !in knownChannels }
        .distinctBy { it.channelId }

/** Long-form uploads from the last seven days with a known date. */
internal fun uploadedThisWeek(
    videos: List<Video>,
    now: Long,
): List<Video> =
    videos.filter {
        !it.isShort && !it.isLive && !it.isUpcoming && it.timestamp > 0L && now - it.timestamp in 0..HomeChipParams.RECENT_WINDOW_MS
    }

/**
 * Taste order with a mild lean towards the newest: a video a week old keeps its rank, one from today
 * moves up by up to a fifth of its position.
 */
internal fun freshnessOrder(
    ranked: List<Video>,
    now: Long,
): List<Video> =
    ranked
        .withIndex()
        .sortedBy { (index, video) ->
            val age = ((now - video.timestamp).toDouble() / HomeChipParams.RECENT_WINDOW_MS).coerceIn(0.0, 1.0)
            index * (0.8 + 0.2 * age)
        }.map { it.value }

internal data class MixSeedCandidate(
    val video: Video,
    val cluster: String,
    val strength: Double,
    val at: Long,
    val longTerm: Boolean,
)

/**
 * Seeds for the mixes: recent strong watches and long-term favourites in turn, one per interest
 * cluster, never a video or channel the viewer rejected.
 */
internal fun mixSeeds(
    candidates: List<MixSeedCandidate>,
    excludedVideos: Set<String>,
    excludedChannels: Set<String>,
    max: Int = HomeChipParams.MIXES_MAX,
): List<MixSeedCandidate> {
    val usable = candidates.filter { it.video.id !in excludedVideos && it.video.channelId !in excludedChannels && !it.video.isShort }
    val recent = ArrayDeque(usable.filter { !it.longTerm }.sortedByDescending { it.at })
    val lasting = ArrayDeque(usable.filter { it.longTerm }.sortedByDescending { it.strength })
    val clusters = HashSet<String>()
    val ids = HashSet<String>()
    val out = mutableListOf<MixSeedCandidate>()

    fun takeFrom(queue: ArrayDeque<MixSeedCandidate>): Boolean {
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (next.video.id in ids || next.cluster in clusters) continue
            ids += next.video.id
            clusters += next.cluster
            out += next
            return true
        }
        return false
    }

    while (out.size < max && (recent.isNotEmpty() || lasting.isNotEmpty())) {
        val tookRecent = out.size < max && takeFrom(recent)
        val tookLasting = out.size < max && takeFrom(lasting)
        if (!tookRecent && !tookLasting) break
    }
    return out
}

/**
 * Watched videos worth watching again: ones rewatched before, finished, not seen for a while and
 * close to current taste. Nothing from the last day, no Shorts, local files or dislikes.
 */
internal fun watchAgain(
    history: List<VideoHistoryEntry>,
    rewatches: Map<String, Int>,
    taste: Map<String, Double>,
    disliked: Set<String>,
    now: Long,
): List<VideoHistoryEntry> =
    history
        .asSequence()
        .filter { !it.isShort && !it.isLocal && it.duration > 0L && it.videoId !in disliked }
        .filter { now - it.timestamp >= HomeChipParams.WATCHED_SKIP_RECENT_MS }
        .sortedByDescending { entry ->
            val repeat = (((rewatches[entry.videoId] ?: 1) - 1) / 3.0).coerceIn(0.0, 1.0)
            val completion = (entry.progressPercentage / 100.0).coerceIn(0.0, 1.0)
            val rest = ((now - entry.timestamp) / (30.0 * 86_400_000.0)).coerceIn(0.0, 1.0)
            0.35 * repeat + 0.25 * completion + 0.15 * rest + 0.25 * (taste[entry.videoId] ?: 0.0)
        }.toList()

/** Loaded chip results, each fresh for its chip's time to live. */
internal class ChipResultCache {
    private val entries = HashMap<String, Pair<ChipFeed, Long>>()

    @Synchronized
    fun get(
        chip: HomeChip,
        now: Long,
    ): ChipFeed? = entries[chip.key]?.takeIf { now - it.second < chip.ttlMs }?.first

    @Synchronized
    fun put(
        chip: HomeChip,
        feed: ChipFeed,
        now: Long,
    ) {
        entries[chip.key] = feed to now
    }

    /** Swaps in an updated result without renewing its time to live. */
    @Synchronized
    fun replace(
        chip: HomeChip,
        feed: ChipFeed,
    ) {
        entries[chip.key]?.let { entries[chip.key] = feed to it.second }
    }

    /** Chips whose fresh result was empty: hidden until it expires. */
    @Synchronized
    fun emptyKeys(
        chips: List<HomeChip>,
        now: Long,
    ): Set<String> = chips.filter { get(it, now) == ChipFeed.Empty }.mapTo(HashSet()) { it.key }
}
