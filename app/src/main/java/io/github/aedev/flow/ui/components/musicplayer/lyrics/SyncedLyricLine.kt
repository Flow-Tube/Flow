package io.github.aedev.flow.ui.components.musicplayer.lyrics

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.LyricsAnimationStyle
import io.github.aedev.flow.data.local.LyricsGlow
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.lyrics.LyricsEntry
import io.github.aedev.flow.data.lyrics.LyricsUtils
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

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
    animation: LyricsAnimationChoice = rememberLyricsAnimationChoice(),
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
                animationStyle = animation.style,
                glow = animation.glow,
            )
        }
        val repeat = remember(lines, shownIndex) { lyricRepeatCount(lines, shownIndex) }
        if (repeat > 1) {
            RepeatedLine(line.text, stringResource(R.string.lyrics_repeat_count, repeat), style, unsungColor, singleLine, karaoke)
        } else if (singleLine) {
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
 * A line that repeats the one before it, with [count] after its last word. The line is laid out
 * narrower by the count's width, so a long line wraps or is cut before it and the count always shows.
 */
@Composable
private fun RepeatedLine(
    text: String,
    count: String,
    style: TextStyle,
    countColor: Color,
    singleLine: Boolean,
    karaoke: @Composable (String) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val measurer = rememberTextMeasurer()
        val widthPx = constraints.maxWidth
        val countLayout = remember(count, style) { measurer.measure(count, style, maxLines = 1, softWrap = false) }
        val gapPx = remember(style) { measurer.measure(" ", style).size.width }
        val room = (widthPx - countLayout.size.width - gapPx).coerceAtLeast(0)
        val shown = remember(text, room, style, singleLine) { if (singleLine) fitOneRow(measurer, text, style, room) else text }
        // Measured as KaraokeLine measures it, so the last row here is the last row it draws.
        val lineLayout =
            remember(shown, room, style) { measurer.measure(shown, style, constraints = Constraints(minWidth = room, maxWidth = room)) }
        val rtlLayout = LocalLayoutDirection.current == LayoutDirection.Rtl
        Box(modifier = Modifier.fillMaxWidth()) {
            // Exactly [room] pixels: a dp round trip could wrap the line differently from [lineLayout].
            Box(
                modifier =
                    Modifier.layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints.copy(minWidth = room, maxWidth = room))
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    },
            ) { karaoke(shown) }
            Text(
                text = count,
                style = style,
                color = countColor,
                maxLines = 1,
                softWrap = false,
                modifier =
                    Modifier.align(AbsoluteAlignment.TopLeft).absoluteOffset {
                        val last = lineLayout.lineCount - 1
                        val lineStart = if (rtlLayout) widthPx - room else 0
                        val x =
                            if (lineLayout.getParagraphDirection(lineLayout.getLineStart(last)) == ResolvedTextDirection.Rtl) {
                                lineStart + lineLayout.getLineLeft(last).toInt() - gapPx - countLayout.size.width
                            } else {
                                lineStart + lineLayout.getLineRight(last).roundToInt() + gapPx
                            }
                        val y = lineLayout.getLineBaseline(last) - countLayout.firstBaseline
                        IntOffset(x.coerceIn(0, (widthPx - countLayout.size.width).coerceAtLeast(0)), y.roundToInt())
                    },
            )
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

/** How synced lines move and glow, as chosen in settings. */
@Immutable
internal data class LyricsAnimationChoice(
    val style: LyricsAnimationStyle,
    val glow: LyricsGlow,
)

/** The lyrics animation the user chose, for a host that shows one line on its own. */
@Composable
internal fun rememberLyricsAnimationChoice(): LyricsAnimationChoice {
    val context = LocalContext.current
    val preferences = remember(context) { PlayerPreferences(context) }
    val style by preferences.lyricsAnimationStyle.collectAsState(initial = LyricsAnimationStyle.CADENCE)
    val glow by preferences.lyricsGlow.collectAsState(initial = LyricsGlow.NORMAL)
    return remember(style, glow) { LyricsAnimationChoice(style, glow) }
}
