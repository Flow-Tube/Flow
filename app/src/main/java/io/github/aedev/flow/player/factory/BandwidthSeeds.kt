package io.github.aedev.flow.player.factory

/**
 * The last bandwidth estimate per network type (`C.NETWORK_TYPE_*`), kept across launches so a
 * session starts near the speed this connection actually had instead of a fixed guess. A seed older
 * than [maxAgeMs] is dropped and Media3's own per-country table answers instead.
 */
internal data class BandwidthSeed(
    val bitsPerSecond: Long,
    val savedAtMs: Long,
)

internal object BandwidthSeeds {
    const val MAX_AGE_MS = 60 * 60 * 1000L
    private const val MIN_BITS_PER_SECOND = 100_000L

    fun decode(raw: String): Map<Int, BandwidthSeed> =
        raw
            .split(';')
            .mapNotNull { entry ->
                val parts = entry.split(':')
                if (parts.size != 3) return@mapNotNull null
                val type = parts[0].toIntOrNull() ?: return@mapNotNull null
                val bits = parts[1].toLongOrNull() ?: return@mapNotNull null
                val savedAt = parts[2].toLongOrNull() ?: return@mapNotNull null
                type to BandwidthSeed(bits, savedAt)
            }.toMap()

    fun encode(seeds: Map<Int, BandwidthSeed>): String =
        seeds.entries
            .sortedBy { it.key }
            .joinToString(";") { (type, seed) -> "$type:${seed.bitsPerSecond}:${seed.savedAtMs}" }

    /** The seeds still young enough to start a session with. */
    fun fresh(
        seeds: Map<Int, BandwidthSeed>,
        nowMs: Long,
        maxAgeMs: Long = MAX_AGE_MS,
    ): Map<Int, Long> =
        seeds
            .filterValues { it.savedAtMs <= nowMs && nowMs - it.savedAtMs <= maxAgeMs && it.bitsPerSecond >= MIN_BITS_PER_SECOND }
            .mapValues { it.value.bitsPerSecond }

    /** [seeds] with [networkType]'s entry replaced, or unchanged when the estimate is not usable. */
    fun record(
        seeds: Map<Int, BandwidthSeed>,
        networkType: Int,
        bitsPerSecond: Long,
        nowMs: Long,
    ): Map<Int, BandwidthSeed> =
        if (networkType <= 0 || bitsPerSecond < MIN_BITS_PER_SECOND) {
            seeds
        } else {
            seeds + (networkType to BandwidthSeed(bitsPerSecond, nowMs))
        }
}
