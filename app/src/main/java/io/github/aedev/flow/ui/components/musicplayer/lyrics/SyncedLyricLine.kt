package io.github.aedev.flow.ui.components.musicplayer.lyrics

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import io.github.aedev.flow.data.lyrics.LyricsEntry
import io.github.aedev.flow.data.lyrics.LyricsUtils
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import kotlinx.coroutines.delay

/** Alpha of the words not sung yet, against the sung colour. */
internal const val SYNCED_LINE_UNSUNG_ALPHA = 0.45f

/** [LyricsUtils.findCurrentLineIndex] moves to a line this early, so the wait ends when it does. */
private const val LINE_LEAD_MS = 300L
private const val MIN_WAIT_MS = 50L

/** A sung line stays up this long after its last word before an instrumental clears it. */
private const val GAP_HOLD_MS = 1_000L

/** Only a pause at least this long between a line's last word and the next line counts as a gap. */
private const val GAP_MIN_MS = 5_000L
private const val ELLIPSIS = "…"

/** A song's synced lines with the offset the user set for it. */
@Immutable
internal data class SyncedLines(
    val lines: List<LyricsEntry>,
    val offsetMs: Long,
)

/**
 * When the line at [index] stops showing before the next one starts: at once for a blank break
 * marker, or a moment after its last word when a long instrumental follows. Null when it stays up.
 */
internal fun lyricGapStartMs(
    lines: List<LyricsEntry>,
    index: Int,
): Long? {
    val line = lines.getOrNull(index) ?: return null
    if (line.text.isBlank()) return line.time
    val lastWordEnd = line.words?.maxOfOrNull { it.endTime } ?: return null
    val next = lines.getOrNull(index + 1)?.time
    if (next != null && next - lastWordEnd < GAP_MIN_MS) return null
    return lastWordEnd + GAP_HOLD_MS
}

/**
 * The line being sung, filled word by word by [KaraokeLine] like the lyrics sheet. It wakes only
 * when the line changes; [positionKey] moves with the player's once-a-second position, so a seek is
 * picked up within a second. Nothing runs while [active] is false or the activity is stopped: the
 * line stays as it was, drawn still, and the word fill runs only while music plays.
 *
 * [clearInGaps] shows [empty] during instrumentals instead of holding the last line, and
 * [singleLine] cuts a long line to one row with an ellipsis.
 */
@Composable
internal fun SyncedLyricLine(
    lyrics: SyncedLines,
    isPlaying: Boolean,
    positionKey: Long,
    style: TextStyle,
    sungColor: Color,
    unsungColor: Color,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    clearInGaps: Boolean = false,
    singleLine: Boolean = false,
    contentAlignment: Alignment = Alignment.BottomStart,
    empty: @Composable () -> Unit,
) {
    val lines = lyrics.lines
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val shown = lifecycle.isAtLeast(Lifecycle.State.STARTED)
    val running = active && shown
    var index by remember(lyrics) { mutableIntStateOf(-1) }
    LaunchedEffect(lyrics, isPlaying, positionKey, running, clearInGaps) {
        // Hidden, the line is left as it was; it catches up the moment it can be seen again.
        if (!running) return@LaunchedEffect
        while (true) {
            val lyricTime = EnhancedMusicPlayerManager.getCurrentPosition() + lyrics.offsetMs
            val current = LyricsUtils.findCurrentLineIndex(lines, lyricTime)
            val gapStart = if (clearInGaps) lyricGapStartMs(lines, current) else null
            index = if (gapStart != null && lyricTime >= gapStart) -1 else current
            if (!isPlaying) break
            val nextLine = lines.getOrNull(current + 1)?.time?.minus(LINE_LEAD_MS)
            val wake = listOfNotNull(nextLine, gapStart?.takeIf { it > lyricTime }).minOrNull() ?: break
            delay((wake - lyricTime).coerceAtLeast(MIN_WAIT_MS))
        }
    }
    val motionEnabled = rememberLyricsMotionEnabled()
    val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    AnimatedContent(
        targetState = index,
        // Unclipped: vowel signs above the line box (Devanagari) and lifted sung words were cut off for
        // as long as each change ran (#2044).
        transitionSpec = { (fadeIn(fade) togetherWith fadeOut(fade)) using SizeTransform(clip = false) },
        modifier = modifier,
        contentAlignment = contentAlignment,
        label = "syncedLyricLine",
    ) { shownIndex ->
        val line = lines.getOrNull(shownIndex)
        if (line == null || line.text.isBlank()) {
            empty()
            return@AnimatedContent
        }
        val nextTime = lines.getOrNull(shownIndex + 1)?.time
        val words =
            remember(line, nextTime) {
                line.words
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { splitTrailingHyphenWord(sanitizeWordTimestamps(it)).first }
                    ?: synthesizeWordTimings(line.text, line.time, nextTime)
            }
        val karaoke: @Composable (String) -> Unit = { text ->
            KaraokeLine(
                text = text,
                words = words,
                style = style,
                sungColor = sungColor,
                unsungColor = unsungColor,
                isCurrent = true,
                isPlaying = isPlaying && running,
                isBackground = false,
                syncOffsetMs = lyrics.offsetMs,
                positionProvider = EnhancedMusicPlayerManager::getCurrentPosition,
                motionEnabled = motionEnabled,
            )
        }
        if (singleLine) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val measurer = rememberTextMeasurer()
                val widthPx = constraints.maxWidth
                val fitted = remember(line.text, widthPx, style) { fitOneRow(measurer, line.text, style, widthPx) }
                karaoke(fitted)
            }
        } else {
            karaoke(line.text)
        }
    }
}

/**
 * [text] cut after the last whole word that fits one row with an ellipsis. The words past the cut
 * no longer match the text, so [KaraokeLine] leaves them out and fills the ellipsis after the last
 * one shown.
 */
private fun fitOneRow(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    widthPx: Int,
): String {
    val whole = measurer.measure(text, style, maxLines = 1, constraints = Constraints(maxWidth = widthPx))
    if (!whole.hasVisualOverflow) return text
    val room = (widthPx - measurer.measure(ELLIPSIS, style).size.width).coerceAtLeast(0)
    val cut = measurer.measure(text, style, maxLines = 1, constraints = Constraints(maxWidth = room))
    return text.substring(0, cut.getLineEnd(0, visibleEnd = true)).trimEnd() + ELLIPSIS
}
