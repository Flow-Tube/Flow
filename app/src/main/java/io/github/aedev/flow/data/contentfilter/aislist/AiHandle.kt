package io.github.aedev.flow.data.contentfilter.aislist

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import java.util.Locale

/**
 * Channel handle normalization, step for step the same as the list pipeline and Flow Desktop
 * (Flow-Tube/AIlist README, "Handle normalization"), or nothing matches. The shared vectors in
 * `normalize-vectors.json` pin it.
 */
internal object AiHandle {
    private const val MAX_LENGTH = 64

    /** The handle without `@`, lowercased, or null when the input is not a usable handle. */
    fun normalize(input: String): String? {
        val decoded = percentDecode(extract(input.trimJs()))
        val handle = Normalizer.normalize(decoded, Normalizer.Form.NFC).lowercase(Locale.ROOT)
        if (handle.isEmpty()) return null
        if (handle.codePointCount(0, handle.length) > MAX_LENGTH) return null
        if (handle.any { it.isJsWhitespace() || it in FORBIDDEN }) return null
        return handle
    }

    /**
     * The handle as a report names it, `@Name` in its own case, or null when it is not one AiSList
     * takes: 3 to 30 letters, digits, marks, `_`, `-`, `.` or `·` after the `@`, as YouTube allows.
     */
    fun forReport(input: String): String? {
        val handle = Normalizer.normalize(percentDecode(extract(input.trimJs())), Normalizer.Form.NFC)
        val length = handle.codePointCount(0, handle.length)
        if (length !in MIN_REPORT_LENGTH..MAX_REPORT_LENGTH) return null
        val allowed =
            handle.codePoints().allMatch { point ->
                Character.isLetterOrDigit(point) ||
                    Character.getType(point).toByte() in MARK_TYPES ||
                    point in REPORT_PUNCTUATION
            }
        return if (allowed) "@$handle" else null
    }

    private const val MIN_REPORT_LENGTH = 3
    private const val MAX_REPORT_LENGTH = 30
    private val MARK_TYPES = setOf(Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK)
    private val REPORT_PUNCTUATION = setOf('_'.code, '-'.code, '.'.code, '·'.code)

    private const val FORBIDDEN = "/%?#@"

    private fun extract(text: String): String {
        val rest =
            when {
                text.startsWith('@') -> text.substring(1)
                else -> text.indexOf("/@").takeIf { it >= 0 }?.let { text.substring(it + 2) } ?: return text
            }
        val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        return if (end < 0) rest else rest.substring(0, end)
    }

    // java.net.URLDecoder turns '+' into a space; decodeURIComponent, which the pipeline uses, does not.
    private fun percentDecode(text: String): String {
        if ('%' !in text) return text
        val out = StringBuilder(text.length)
        val pending = ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%') {
                val byte = text.hexByteAt(i + 1) ?: return text
                pending.write(byte)
                i += 3
                continue
            }
            if (pending.size() > 0) {
                out.append(pending.decodeStrict() ?: return text)
                pending.reset()
            }
            out.append(c)
            i++
        }
        if (pending.size() > 0) out.append(pending.decodeStrict() ?: return text)
        return out.toString()
    }

    private fun String.hexByteAt(index: Int): Int? {
        if (index + 1 >= length) return null
        val high = Character.digit(this[index], 16)
        val low = Character.digit(this[index + 1], 16)
        return if (high < 0 || low < 0) null else high * 16 + low
    }

    private fun ByteArrayOutputStream.decodeStrict(): String? =
        try {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(toByteArray()))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }

    private fun String.trimJs(): String = trim { it.isJsWhitespace() }

    // JavaScript's \s, which String.trim() and the pipeline's rejection rule use. Char.isWhitespace()
    // disagrees on U+00A0, U+2007, U+202F and U+001C to U+001F.
    private fun Char.isJsWhitespace(): Boolean =
        this in '\u0009'..'\u000D' ||
            this == ' ' ||
            this == ' ' ||
            this == ' ' ||
            this in ' '..' ' ||
            this == ' ' ||
            this == ' ' ||
            this == ' ' ||
            this == ' ' ||
            this == '　' ||
            this == '﻿'
}
