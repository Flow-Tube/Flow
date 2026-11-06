package io.github.aedev.flow.ui.components.musicplayer.lyrics

import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import io.github.aedev.flow.data.local.LyricsBackgroundStyle
import io.github.aedev.flow.data.lyrics.LyricsEntry
import io.github.aedev.flow.data.lyrics.WordTimestamp
import io.github.aedev.flow.ui.theme.ensureContrastOn
import io.github.aedev.flow.ui.theme.withTone

/** Which side of the panel a singer's lines sit on in a duet. */
internal enum class DuetSide { START, END, BOTH }

/**
 * Sides for a duet, one per line: the first singer to appear sits at the start, the second at the
 * end, and any other tag (Apple's "sung together" among them) in the middle. Background vocals
 * follow the line they belong to. Null when fewer than two singers are tagged.
 */
internal fun duetSides(lines: List<LyricsEntry>): List<DuetSide?>? {
    val singers =
        lines
            .filter { !it.isBackground }
            .mapNotNull { line -> line.agent?.takeIf { it.isNotBlank() && it != "bg" } }
            .distinct()
    if (singers.size < 2) return null
    var previous: DuetSide? = null
    return lines.map { line ->
        if (line.isBackground) {
            previous
        } else {
            when (line.agent) {
                singers[0] -> DuetSide.START
                singers[1] -> DuetSide.END
                null, "" -> previous ?: DuetSide.START
                else -> DuetSide.BOTH
            }.also { previous = it }
        }
    }
}

/** The start time of the next main line after each line, which bounds how long it is sung. */
internal fun nextMainLineTimes(lines: List<LyricsEntry>): List<Long?> {
    val result = arrayOfNulls<Long>(lines.size)
    var next: Long? = null
    for (i in lines.indices.reversed()) {
        result[i] = next
        if (!lines[i].isBackground) next = lines[i].time
    }
    return result.toList()
}

/** A Latin-script line for lyrics in other scripts, made on the device by the platform's ICU. */
internal object LyricsRomanizer {
    val isAvailable: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    private val transliterator by lazy {
        if (isAvailable) {
            android.icu.text.Transliterator
                .getInstance("Any-Latin; Latin-ASCII")
        } else {
            null
        }
    }

    fun transliterate(text: String): String? {
        if (!needsRomanization(text)) return null
        val transliterator = transliterator ?: return null
        return transliterator
            .transliterate(spaceBetweenScripts(text))
            .trim()
            .takeIf { it.isNotBlank() && it != text }
    }
}

/**
 * Whether the lyrics are Japanese, decided once for the whole song: many Japanese lines are kanji
 * alone, and ICU reads kanji as Mandarin (#1352). A stray の in Chinese lyrics does not count.
 */
internal fun isJapaneseLyrics(lines: List<String>): Boolean {
    var kana = 0
    var han = 0
    lines.forEach { line ->
        line.codePoints().forEach { codePoint ->
            when (Character.UnicodeScript.of(codePoint)) {
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> kana++
                Character.UnicodeScript.HAN -> han++
                else -> Unit
            }
        }
    }
    return kana >= MIN_JAPANESE_KANA && kana * 4 >= han
}

private const val MIN_JAPANESE_KANA = 2

/** A space where a Latin letter meets a letter of another script, which ICU would run together ("ikureason"). */
internal fun spaceBetweenScripts(text: String): String {
    val spaced = StringBuilder(text.length + 4)
    var previous = -1
    text.codePoints().forEach { codePoint ->
        if (previous >= 0 && Character.isLetter(previous) && Character.isLetter(codePoint) && isLatin(previous) != isLatin(codePoint)) {
            spaced.append(' ')
        }
        spaced.appendCodePoint(codePoint)
        previous = codePoint
    }
    return spaced.toString()
}

private fun isLatin(codePoint: Int): Boolean = Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN

internal fun needsRomanization(text: String): Boolean =
    text.any { it.isLetter() && Character.UnicodeScript.of(it.code) != Character.UnicodeScript.LATIN }

/** How the lyrics are dressed beyond the words themselves; the user's choices from the lyrics menu. */
internal data class LyricsDisplayOptions(
    val showTranslation: Boolean = true,
    val showRomanization: Boolean = true,
    val autoRomanize: Boolean = false,
    val romanizationOnly: Boolean = false,
    val keepScreenOn: Boolean = false,
    val blurOtherLines: Boolean = true,
    val background: LyricsBackgroundStyle = LyricsBackgroundStyle.SOLID,
)

/**
 * The Latin-script line under each lyric: the source's own when it has one, otherwise made on the
 * device when [LyricsDisplayOptions.autoRomanize] is on, except for Japanese, whose kanji the device
 * cannot read. Transliteration is slow enough for a whole song that callers run this off the main thread.
 */
internal fun lyricsRomanizations(
    lines: List<LyricsEntry>,
    options: LyricsDisplayOptions,
    transliterate: (String) -> String? = LyricsRomanizer::transliterate,
): List<String?> {
    val onDevice = options.showRomanization && options.autoRomanize && !isJapaneseLyrics(lines.map { it.text })
    return lines.map { line ->
        when {
            !options.showRomanization -> null
            !line.romanization.isNullOrBlank() -> line.romanization
            onDevice -> transliterate(line.text)
            else -> null
        }
    }
}

/**
 * The source's word timings carried onto [romanized], when it splits into as many words as they do.
 * Null otherwise: a Chinese line is timed per character but romanizes into fewer words.
 */
internal fun romanizedWordTimings(
    romanized: String,
    words: List<WordTimestamp>,
): List<WordTimestamp>? {
    val tokens = romanized.split(WhiteSpace).filter(String::isNotBlank)
    if (tokens.size != words.size) return null
    return words.mapIndexed { index, word -> word.copy(text = tokens[index]) }
}

private val WhiteSpace = Regex("\\s+")

/** Everything the rows need to dress each line, worked out once per lyrics and settings. */
internal class LyricsPresentation(
    val looks: List<LyricsLineLook>,
    val nextLineTimes: List<Long?>,
    val romanizations: List<String?>,
    val romanizationOnly: Boolean,
    val showTranslation: Boolean,
)

/**
 * The accent the lyrics read in: the player's accent, lifted until it clears 4.5:1 against the
 * backdrop. The second singer of a duet takes the backdrop's own hue at a light tone, so the two
 * voices read apart without leaving the artwork's palette.
 */
internal fun lyricsPresentation(
    lines: List<LyricsEntry>,
    accent: Color,
    backdrop: Color?,
    baseTextSize: Float,
    userAlign: TextAlign,
    showTranslation: Boolean,
    romanizations: List<String?>,
    romanizationOnly: Boolean,
): LyricsPresentation {
    val primary = backdrop?.let { ensureContrastOn(accent, it, minRatio = 4.5f) } ?: accent
    val secondary = backdrop?.let { ensureContrastOn(it.withTone(88.0), it, minRatio = 4.5f) } ?: primary
    val sides = duetSides(lines)
    val looks =
        lines.indices.map { i ->
            when (sides?.getOrNull(i)) {
                DuetSide.START -> LyricsLineLook(baseTextSize, LINE_SPACING, primary, TextAlign.Start, backdrop)
                DuetSide.END -> LyricsLineLook(baseTextSize, LINE_SPACING, secondary, TextAlign.End, backdrop)
                DuetSide.BOTH -> LyricsLineLook(baseTextSize, LINE_SPACING, primary, TextAlign.Center, backdrop)
                null -> LyricsLineLook(baseTextSize, LINE_SPACING, primary, userAlign, backdrop)
            }
        }
    return LyricsPresentation(looks, nextMainLineTimes(lines), romanizations, romanizationOnly, showTranslation)
}

private const val LINE_SPACING = 1.3f

/** False when the system's animations are turned off: lyrics then fill without lift, glow or blur transitions. */
@Composable
internal fun rememberLyricsMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
}
