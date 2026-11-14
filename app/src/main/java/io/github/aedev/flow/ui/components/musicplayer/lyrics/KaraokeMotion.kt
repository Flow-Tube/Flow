package io.github.aedev.flow.ui.components.musicplayer.lyrics

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

internal const val GLOW_LEVELS = 64

/**
 * Which prepared glow strength a held word shows at [progress] through it, or -1 for none. It rises
 * and falls with the word on one smooth curve, in steps too small to see.
 */
internal fun heldNoteGlowLevel(progress: Float): Int {
    if (progress <= 0f || progress >= 1f) return -1
    return (sin(progress * PI).toFloat() * GLOW_LEVELS).toInt().coerceIn(0, GLOW_LEVELS - 1)
}

/** How far the lift has travelled [elapsedMs] after a word starts: an underdamped spring's step response. */
internal fun wordLiftProgress(elapsedMs: Long): Float {
    if (elapsedMs <= 0) return 0f
    val t = elapsedMs / 1000.0
    val zeta = 0.55
    val omega = 2 * PI / 0.42
    val damped = omega * sqrt(1 - zeta * zeta)
    val decay = exp(-zeta * omega * t)
    return (1 - decay * (cos(damped * t) + zeta / sqrt(1 - zeta * zeta) * sin(damped * t))).toFloat()
}

internal fun wordFillProgress(
    geometry: KaraokeGeometry,
    cell: KaraokeCell,
    word: KaraokeWord?,
    position: Long,
    isCurrent: Boolean,
): Float =
    when {
        !isCurrent -> 0f
        word == null -> if (position >= (geometry.words.getOrNull(cell.wordBefore)?.endMs ?: Long.MAX_VALUE)) 1f else 0f
        position < word.startMs -> 0f
        position >= word.endMs -> 1f
        else -> (position - word.startMs).toFloat() / (word.endMs - word.startMs).coerceAtLeast(1)
    }

internal fun cellFillProgress(
    cell: KaraokeCell,
    progress: Float,
): Float = ((progress - cell.startFraction) / cell.widthFraction).coerceIn(0f, 1f)

internal fun wordLiftPx(
    word: KaraokeWord?,
    liftClock: Long,
    liftPx: Float,
    lift: Float,
): Float = if (word == null || liftClock == Long.MIN_VALUE) 0f else liftPx * lift * wordLiftProgress(liftClock - word.startMs)
