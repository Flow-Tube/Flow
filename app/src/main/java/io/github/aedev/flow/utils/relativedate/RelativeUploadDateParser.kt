/*
 * Copyright (C) 2025-2026 Flow | A-EDev
 *
 * This file is part of Flow (https://github.com/Flow-Tube/Flow).
 */

package io.github.aedev.flow.utils.relativedate

import java.util.Locale

/**
 * Parses relative upload-date text ("3 weeks ago", "hace 3 semanas", "3日前") into an epoch-millis
 * timestamp, reading it in the host language ([hl]) the request was made with and then in English.
 * Returns null when the text carries no parsable age: callers must NOT substitute "now" for unknown,
 * or every stale item reads as new.
 */
object RelativeUploadDateParser {
    fun parse(
        textualDate: String?,
        hl: String?,
        now: Long = System.currentTimeMillis(),
    ): Long? = read(textualDate, hl, now)?.timestamp

    /** [parse], keeping the unit the age was given in: "1 year ago" places the upload within a year, not on a day. */
    internal fun read(
        textualDate: String?,
        hl: String?,
        now: Long = System.currentTimeMillis(),
    ): RelativeUploadAge? {
        val text = normalize(textualDate ?: return null)
        if (text.isEmpty()) return null
        val vocabulary = vocabularyFor(hl)
        if (vocabulary != null && vocabulary !== english) {
            (match(text, vocabulary) ?: matchAbbreviated(text, vocabulary))
                ?.let { (unit, amount) -> return RelativeUploadAge(now - unit.millis * amount, unit) }
        }
        return parseEnglish(text, now)
    }

    private val english = RelativeDatePatterns.byTag.getValue("en")

    private val aliases =
        mapOf(
            "in" to "id",
            "he" to "iw",
            "nb" to "no",
            "nn" to "no",
            "tl" to "fil",
            "yue" to "zh-hk",
            "zh" to "zh-cn",
            "zh-hans" to "zh-cn",
            "zh-hant" to "zh-tw",
            "zh-mo" to "zh-hk",
            "zh-sg" to "zh-cn",
        )

    // English shorthand ("3d", "5h ago") only as the whole text: as free words its single letters
    // turn up in other languages ("18 h", Spanish "y").
    private val compactEnglish = Regex("""(\d+)\s*(mo|s|m|h|d|w|y)(\s+ago)?""")

    private val compactUnits =
        mapOf(
            "s" to RelativeDateUnit.SECOND,
            "m" to RelativeDateUnit.MINUTE,
            "h" to RelativeDateUnit.HOUR,
            "d" to RelativeDateUnit.DAY,
            "w" to RelativeDateUnit.WEEK,
            "mo" to RelativeDateUnit.MONTH,
            "y" to RelativeDateUnit.YEAR,
        )

    private fun vocabularyFor(hl: String?): RelativeDateVocabulary? {
        val parts =
            hl
                ?.trim()
                ?.replace('_', '-')
                ?.lowercase(Locale.ROOT)
                ?.split('-')
                ?.filter(String::isNotEmpty)
                .orEmpty()
        return (parts.size downTo 1)
            .asSequence()
            .map { parts.take(it).joinToString("-") }
            .firstNotNullOfOrNull { tag ->
                RelativeDatePatterns.byTag[tag] ?: aliases[tag]?.let(RelativeDatePatterns.byTag::get)
            }
    }

    private fun parseEnglish(
        text: String,
        now: Long,
    ): RelativeUploadAge? {
        if (containsWord(text, "just now")) return RelativeUploadAge(now, RelativeDateUnit.SECOND)
        if (containsWord(text, "today")) return RelativeUploadAge(now, RelativeDateUnit.DAY)
        if (containsWord(text, "yesterday")) return RelativeUploadAge(now - RelativeDateUnit.DAY.millis, RelativeDateUnit.DAY)
        match(text, english)?.let { (unit, amount) -> return RelativeUploadAge(now - unit.millis * amount, unit) }
        val compact = compactEnglish.matchEntire(text) ?: return null
        val amount = compact.groupValues[1].toLongOrNull() ?: return null
        val unit = compactUnits.getValue(compact.groupValues[2])
        return RelativeUploadAge(now - unit.millis * amount, unit)
    }

    private fun match(
        text: String,
        vocabulary: RelativeDateVocabulary,
    ): Pair<RelativeDateUnit, Long>? {
        vocabulary.fixed.entries
            .firstOrNull { (phrase, _) -> contains(text, phrase, vocabulary.spaced) }
            ?.let { (_, fixed) -> return fixed.first to fixed.second.toLong() }
        val unit =
            vocabulary.units
                .firstOrNull { (_, words) -> words.any { contains(text, it, vocabulary.spaced) } }
                ?.first
                ?: return null
        // No numeral means one: "a day ago", "hace un mes", "منذ ساعة".
        val amount = leadingAmount(text) ?: 1L
        return unit to amount
    }

    // An abbreviated age carries exactly one number; a premiere time ("le 12 oct. à 18 h") or a
    // view count ("1,3 M") carries two runs of digits and is left alone.
    private fun matchAbbreviated(
        text: String,
        vocabulary: RelativeDateVocabulary,
    ): Pair<RelativeDateUnit, Long>? {
        if (vocabulary.abbreviated.isEmpty() || digitRuns(text) != 1) return null
        for ((unit, abbreviations) in vocabulary.abbreviated) {
            for (abbreviation in abbreviations) {
                var from = 0
                while (true) {
                    val start = text.indexOf(abbreviation, from)
                    if (start < 0) break
                    from = start + 1
                    val end = start + abbreviation.length
                    if (end < text.length && text[end].isWordPart()) continue
                    amountBefore(text, start)?.let { return unit to it }
                }
            }
        }
        return null
    }

    private fun amountBefore(
        text: String,
        index: Int,
    ): Long? {
        val end = if (index > 0 && text[index - 1] == ' ') index - 1 else index
        var start = end
        while (start > 0 && text[start - 1].isDigit()) start--
        return if (start == end) null else readNumber(text, start)
    }

    private fun digitRuns(text: String): Int = text.indices.count { text[it].isDigit() && (it == 0 || !text[it - 1].isDigit()) }

    private fun contains(
        text: String,
        phrase: String,
        spaced: Boolean,
    ): Boolean = if (spaced) containsWord(text, phrase) else text.contains(phrase)

    // Word edges are checked by hand rather than with \b: Android's ICU regex and the JVM the tests
    // run on disagree about it outside Latin script, and a mark inside a word must not end it.
    private fun containsWord(
        text: String,
        word: String,
    ): Boolean {
        var from = 0
        while (true) {
            val start = text.indexOf(word, from)
            if (start < 0) return false
            val end = start + word.length
            val openBefore = start == 0 || !text[start - 1].isWordPart()
            val openAfter = end == text.length || !text[end].isWordPart()
            if (openBefore && openAfter) return true
            from = start + 1
        }
    }

    private fun Char.isWordPart(): Boolean =
        isLetter() ||
            when (Character.getType(this).toByte()) {
                Character.NON_SPACING_MARK,
                Character.COMBINING_SPACING_MARK,
                Character.ENCLOSING_MARK,
                -> true

                else -> false
            }

    /** The first run of digits in any script: YouTube writes Persian and Khmer ages in their own numerals. */
    private fun leadingAmount(text: String): Long? {
        val start = text.indexOfFirst(Char::isDigit).takeIf { it >= 0 } ?: return null
        return readNumber(text, start)
    }

    private fun readNumber(
        text: String,
        start: Int,
    ): Long? {
        var value = 0L
        var index = start
        while (index < text.length && text[index].isDigit()) {
            value = value * 10 + Character.digit(text[index], 10)
            if (value > MAX_AMOUNT) return null
            index++
        }
        return value
    }

    private fun normalize(raw: String): String =
        raw
            .filterNot { it in ZERO_WIDTH }
            .map { if (it.isWhitespace() || Character.isSpaceChar(it)) ' ' else it }
            .joinToString("")
            .lowercase(Locale.ROOT)
            .split(' ')
            .filter(String::isNotEmpty)
            .joinToString(" ")

    private const val ZERO_WIDTH = "\u200B\u200C\u200D\uFEFF"
    private const val MAX_AMOUNT = 100_000L
}

internal class RelativeUploadAge(
    val timestamp: Long,
    val unit: RelativeDateUnit,
) {
    /** Under a day the calendar date follows from the age; "3 days ago" could be either of two dates. */
    val placesCalendarDay: Boolean get() = unit.millis < RelativeDateUnit.DAY.millis
}
