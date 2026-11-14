package io.github.aedev.flow.ui.components.musicplayer.lyrics

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.ui.text.style.TextAlign
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

internal const val GLOW_LEVELS = 64

private const val RIPPLE_CREST = 0.4f
private const val RIPPLE_SETTLE = 0.4f
private const val RIPPLE_MIN_MS = 420f
private const val RIPPLE_MAX_MS = 900f
private const val SWELL_MIN_MS = 900f
private const val SWELL_FULL_MS = 1_800f
private const val SWELL_PEAK = 0.035f
private const val CRESCENDO_STEP = 0.012f
private const val CRESCENDO_POP = 0.06f
private const val CRESCENDO_RELEASE_MS = 600f
private const val EXIT_FILL_MS = 180f

/** How long a Cadence line takes to let go once the next line starts: the fill finishes, then the sung colour fades. */
internal const val CADENCE_EXIT_MS = 680

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

/**
 * How high a letter of a Cadence line stands, as a share of the peak lift. It starts rising as the
 * fill's soft edge reaches the letter ([from], [feather] early) and is at its crest the moment the
 * letter is fully lit ([to]); then it settles a little raised, so sung text sits above what is still
 * to come. The rise outlasts a letter's slot, so neighbouring letters move together like a wave.
 */
internal fun rippleLift(
    positionMs: Long,
    wordStartMs: Long,
    wordEndMs: Long,
    from: Float,
    to: Float,
    feather: Float,
): Float {
    val touch = rippleTouchMs(wordStartMs, wordEndMs, from, feather)
    val lit = wordStartMs + (wordEndMs - wordStartMs).coerceAtLeast(1) * (to + feather)
    val wave = ((lit - touch) / RIPPLE_CREST).coerceIn(RIPPLE_MIN_MS, RIPPLE_MAX_MS)
    val progress = (positionMs - touch) / wave
    return when {
        progress <= 0f -> 0f
        progress < RIPPLE_CREST -> FastOutSlowInEasing.transform(progress / RIPPLE_CREST)
        progress < 1f -> 1f - (1f - RIPPLE_SETTLE) * LinearOutSlowInEasing.transform((progress - RIPPLE_CREST) / (1f - RIPPLE_CREST))
        else -> RIPPLE_SETTLE
    }
}

/** When the letter whose ink starts at [from] begins to rise. */
internal fun rippleTouchMs(
    wordStartMs: Long,
    wordEndMs: Long,
    from: Float,
    feather: Float,
): Float = wordStartMs + (wordEndMs - wordStartMs).coerceAtLeast(1) * (from - feather)

/** After this, every letter of the word up to [to] has settled at the same height. */
internal fun rippleSettledMs(
    wordStartMs: Long,
    wordEndMs: Long,
    to: Float,
    feather: Float,
): Float = wordStartMs + (wordEndMs - wordStartMs).coerceAtLeast(1) * (to + feather) + RIPPLE_MAX_MS

/** A long held note grows a little from its baseline as it is sung, most in the middle of the note. */
internal fun heldNoteSwell(
    positionMs: Long,
    startMs: Long,
    endMs: Long,
): Float {
    val duration = (endMs - startMs).toFloat()
    if (duration < SWELL_MIN_MS || positionMs <= startMs || positionMs >= endMs) return 1f
    val strength = ((duration - SWELL_MIN_MS) / (SWELL_FULL_MS - SWELL_MIN_MS)).coerceIn(0f, 1f)
    val progress = (positionMs - startMs) / duration
    return 1f + SWELL_PEAK * strength * sin(PI * progress).toFloat()
}

/**
 * A run of syllables sung apart ("so-o-o") climbs: each one is a step bigger than the one before, the
 * last pops, and when the run ends they all spring back down together.
 */
internal fun crescendoScale(
    positionMs: Long,
    word: KaraokeWord,
): Float {
    if (word.group < 0 || positionMs < word.startMs) return 1f
    val step = CRESCENDO_STEP * word.group
    val pop =
        if (word.group == word.groupSize - 1) {
            val progress = ((positionMs - word.startMs).toFloat() / (word.endMs - word.startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
            CRESCENDO_POP * LinearOutSlowInEasing.transform(progress)
        } else {
            0f
        }
    if (positionMs <= word.groupEndMs) return 1f + step + pop
    val release = ((positionMs - word.groupEndMs) / CRESCENDO_RELEASE_MS).coerceIn(0f, 1f)
    val spring = (exp(-3.5 * release) * cos(5 * PI * release) * (1 - release)).toFloat()
    return 1f + (step + pop) * spring
}

/**
 * Which share of a row's extra width moves it the other way when words grow, so the row stays where
 * its alignment holds it: none for a row held at its left edge, half for a centred one, all for one
 * held at its right edge.
 */
internal fun reflowAnchor(
    align: TextAlign,
    rtl: Boolean,
): Float =
    when (align) {
        TextAlign.Center, TextAlign.Justify -> 0.5f
        TextAlign.Right -> 1f
        TextAlign.End -> if (rtl) 0f else 1f
        TextAlign.Start -> if (rtl) 1f else 0f
        else -> 0f
    }

/** How far an ending line's fill is pushed on [exit] of its letting go: it completes in the first 180 ms. */
internal fun exitFill(exit: Float): Float = (exit * CADENCE_EXIT_MS / EXIT_FILL_MS).coerceIn(0f, 1f)

/** How much of the sung colour an ending line still shows: all of it until the fill completes, then fading out. */
internal fun exitLitAlpha(exit: Float): Float {
    val fade = ((exit * CADENCE_EXIT_MS - EXIT_FILL_MS) / (CADENCE_EXIT_MS - EXIT_FILL_MS)).coerceIn(0f, 1f)
    return 1f - FastOutSlowInEasing.transform(fade)
}
