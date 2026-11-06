package io.github.aedev.flow.data.lyrics

/**
 * Utility functions for parsing lyrics in various formats.
 * Supports standard LRC, rich sync (word-by-word), and plain text.
 */
object LyricsUtils {
    private val LINE_REGEX = "((\\[\\d{1,2}:\\d{2}[.:]\\d{2,3}\\] ?)+)(.*)".toRegex()
    private val TIME_REGEX = "\\[(\\d{1,2}):(\\d{2})[.:](\\d{2,3})\\]".toRegex()

    private val RICH_SYNC_LINE_REGEX = "\\[(\\d{1,2}):(\\d{2})\\.(\\d{2,3})\\](.*)".toRegex()
    private val RICH_SYNC_WORD_REGEX = "<(\\d{1,2}):(\\d{2})\\.(\\d{2,3})>\\s*([^<]+)".toRegex()
    private val PAXSENIX_AGENT_LINE_REGEX = "\\[(\\d{1,2}):(\\d{2})\\.(\\d{2,3})\\](v\\d+):\\s*(.*)".toRegex()
    private val PAXSENIX_BG_LINE_REGEX = "^\\[bg:\\s*(.*)\\]$".toRegex()
    private val AGENT_REGEX = "\\{agent:([^}]+)\\}".toRegex()
    private val BACKGROUND_REGEX = "^\\{bg\\}".toRegex()
    private val HTML_NUMERIC_ENTITY_REGEX = "&#(x?[0-9A-Fa-f]+);".toRegex()
    private val HTML_NAMED_ENTITIES =
        mapOf(
            "&amp;" to "&",
            "&apos;" to "'",
            "&#39;" to "'",
            "&quot;" to "\"",
            "&lt;" to "<",
            "&gt;" to ">",
            "&nbsp;" to " ",
        )

    fun decodeHtmlEntities(text: String): String {
        var decoded = text
        repeat(2) {
            HTML_NAMED_ENTITIES.forEach { (entity, replacement) ->
                decoded = decoded.replace(entity, replacement)
            }
            decoded =
                HTML_NUMERIC_ENTITY_REGEX.replace(decoded) { match ->
                    val raw = match.groupValues[1]
                    val codePoint =
                        if (raw.startsWith("x", ignoreCase = true)) {
                            raw.drop(1).toIntOrNull(16)
                        } else {
                            raw.toIntOrNull()
                        }
                    codePoint
                        ?.takeIf { Character.isValidCodePoint(it) }
                        ?.let { String(Character.toChars(it)) }
                        ?: match.value
                }
        }
        return decoded
    }

    /**
     * Parse lyrics string into a list of LyricsEntry objects.
     * Auto-detects rich sync vs standard LRC format.
     */
    fun parseLyrics(lyrics: String): List<LyricsEntry> {
        val unescaped =
            lyrics
                .trim()
                .removePrefix("\"")
                .removeSuffix("\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")

        val lines =
            unescaped
                .lines()
                .filter { it.isNotBlank() && !it.trim().startsWith("[offset:") }

        val isRichSync =
            lines.any { line ->
                (RICH_SYNC_LINE_REGEX.matches(line.trim()) && RICH_SYNC_WORD_REGEX.containsMatchIn(line)) ||
                    PAXSENIX_AGENT_LINE_REGEX.containsMatchIn(line.trim()) ||
                    PAXSENIX_BG_LINE_REGEX.containsMatchIn(line.trim())
            }

        return if (isRichSync) {
            parseRichSyncLyrics(lines)
        } else {
            parseStandardLyrics(lines)
        }
    }

    /**
     * Parse rich sync lyrics: [MM:SS.mm]<MM:SS.mm> word <MM:SS.mm> word ...
     */
    private fun parseRichSyncLyrics(lines: List<String>): List<LyricsEntry> {
        val result = mutableListOf<LyricsEntry>()
        var lastNonBgAgent: String? = null

        lines.forEachIndexed { index, line ->
            val trimmedLine = line.trim()

            PAXSENIX_BG_LINE_REGEX.find(trimmedLine)?.let { bgMatch ->
                val content = bgMatch.groupValues[1]
                val wordTimings =
                    parseRichSyncWords(content, index, lines)
                        ?: parseFollowingWordBlock(index, lines)
                val plainText = stripRichSyncTags(content)
                val lineTimeMs = wordTimings?.firstOrNull()?.startTime ?: 0L
                if (plainText.isNotBlank()) {
                    result.add(
                        LyricsEntry(
                            time = lineTimeMs,
                            text = plainText,
                            words = wordTimings,
                            agent = lastNonBgAgent ?: "bg",
                            isBackground = true,
                        ),
                    )
                }
                return@forEachIndexed
            }

            PAXSENIX_AGENT_LINE_REGEX.find(trimmedLine)?.let { agentMatch ->
                val lineTimeMs = parseTimestamp(agentMatch.groupValues)
                val agent = agentMatch.groupValues[4]
                val content = agentMatch.groupValues[5]
                val wordTimings =
                    parseRichSyncWords(content, index, lines)
                        ?: parseFollowingWordBlock(index, lines)
                val plainText = stripRichSyncTags(content)
                if (agent.isNotBlank()) lastNonBgAgent = agent
                if (plainText.isNotBlank()) {
                    result.add(
                        LyricsEntry(
                            time = lineTimeMs,
                            text = plainText,
                            words = wordTimings,
                            agent = agent,
                            isBackground = false,
                        ),
                    )
                }
                return@forEachIndexed
            }

            val matchResult = RICH_SYNC_LINE_REGEX.matchEntire(trimmedLine)
            if (matchResult != null) {
                val minutes = matchResult.groupValues[1].toLongOrNull() ?: 0L
                val seconds = matchResult.groupValues[2].toLongOrNull() ?: 0L
                val fraction = matchResult.groupValues[3].toLongOrNull() ?: 0L

                val millisPart = if (matchResult.groupValues[3].length == 3) fraction else fraction * 10
                val lineTimeMs = minutes * 60000 + seconds * 1000 + millisPart

                var content = matchResult.groupValues[4].trimStart()

                val agentMatch = AGENT_REGEX.find(content)
                val agent = agentMatch?.groupValues?.get(1)
                if (agentMatch != null) {
                    content = content.replaceFirst(AGENT_REGEX, "")
                }

                val isBackground = BACKGROUND_REGEX.containsMatchIn(content)
                if (isBackground) {
                    content = content.replaceFirst(BACKGROUND_REGEX, "")
                }

                val wordTimings =
                    parseRichSyncWords(content, index, lines)
                        ?: parseFollowingWordBlock(index, lines)

                val plainText = stripRichSyncTags(content)

                if (plainText.isNotBlank()) {
                    if (!isBackground && !agent.isNullOrBlank()) lastNonBgAgent = agent
                    result.add(
                        LyricsEntry(
                            time = lineTimeMs,
                            text = plainText,
                            words = wordTimings,
                            agent = if (isBackground) lastNonBgAgent ?: "bg" else agent,
                            isBackground = isBackground,
                        ),
                    )
                }
            }
        }

        return mergeRepeatedTimestampTranslations(result.sorted())
    }

    private fun mergeRepeatedTimestampTranslations(entries: List<LyricsEntry>): List<LyricsEntry> {
        if (entries.size < 2) return entries

        val merged = mutableListOf<LyricsEntry>()
        var index = 0
        while (index < entries.size) {
            var base = entries[index]
            var nextIndex = index + 1
            while (
                nextIndex < entries.size &&
                entries[nextIndex].time == base.time &&
                !base.isBackground &&
                !entries[nextIndex].isBackground
            ) {
                val candidate = entries[nextIndex]
                if (candidate.words.isNullOrEmpty() && candidate.text.isNotBlank()) {
                    base =
                        base.copy(
                            translation =
                                listOfNotNull(base.translation, candidate.text)
                                    .joinToString("\n")
                                    .ifBlank { null },
                        )
                    nextIndex++
                } else {
                    break
                }
            }
            merged += base
            index = nextIndex
        }
        return merged
    }

    /**
     * Parse word timestamps from rich sync content.
     */
    private fun parseRichSyncWords(
        content: String,
        currentIndex: Int,
        allLines: List<String>,
    ): List<WordTimestamp>? {
        val matches = RICH_SYNC_WORD_REGEX.findAll(content).toList()

        if (matches.isEmpty()) return null

        val wordTimings = mutableListOf<WordTimestamp>()
        val trailingEndTime = parseTrailingRichSyncEndTime(content, matches.last().range.last + 1)

        matches.forEachIndexed { index, match ->
            val startTime = parseTimestamp(match.groupValues)
            val nextStart =
                if (index < matches.lastIndex) {
                    parseTimestamp(matches[index + 1].groupValues)
                } else {
                    trailingEndTime
                        ?: getNextLineStartTimeMs(currentIndex, allLines)?.takeIf { it > startTime }
                        ?: (startTime + 500)
                }

            val rawText =
                match.groupValues[4]
                    .replace(Regex("\\[(\\d{1,2}):(\\d{2})\\.(\\d{2,3})\\]\\s*$"), "")
            val words = rawText.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            words.forEachIndexed { wordIndex, word ->
                val wordStart = startTime + ((nextStart - startTime) * wordIndex / words.size.coerceAtLeast(1))
                val wordEnd = startTime + ((nextStart - startTime) * (wordIndex + 1) / words.size.coerceAtLeast(1))
                wordTimings.add(WordTimestamp(word, wordStart, wordEnd))
            }
        }

        return wordTimings.takeIf { it.isNotEmpty() }
    }

    private fun stripRichSyncTags(content: String): String =
        content
            .replace(Regex("<\\d{1,2}:\\d{2}\\.\\d{2,3}>\\s*"), " ")
            .replace(Regex("\\[\\d{1,2}:\\d{2}\\.\\d{2,3}\\]\\s*$"), " ")
            .replace(Regex("\\s+"), " ")
            .replace(Regex("\\s+([,.;:!?%])"), "$1")
            .trim()

    private fun parseTrailingRichSyncEndTime(
        content: String,
        fromIndex: Int,
    ): Long? {
        val trailing = content.substring(fromIndex).trim()
        val match =
            Regex("^(?:<|\\[)(\\d{1,2}):(\\d{2})\\.(\\d{2,3})(?:>|\\])$").find(trailing)
                ?: return null
        return parseTimestamp(match.groupValues)
    }

    private fun parseFollowingWordBlock(
        currentIndex: Int,
        allLines: List<String>,
    ): List<WordTimestamp>? {
        val nextLine = allLines.getOrNull(currentIndex + 1)?.trim() ?: return null
        return if (nextLine.startsWith("<") && nextLine.endsWith(">")) {
            parseMetrolistWordTimestamps(nextLine.removeSurrounding("<", ">"))
        } else {
            null
        }
    }

    private fun parseTimestamp(parts: List<String>): Long {
        val min = parts[1].toLongOrNull() ?: 0L
        val sec = parts[2].toLongOrNull() ?: 0L
        val frac = parts[3].toLongOrNull() ?: 0L
        val millis = if (parts[3].length == 3) frac else frac * 10
        return min * 60000 + sec * 1000 + millis
    }

    private fun getNextLineStartTimeMs(
        currentIndex: Int,
        allLines: List<String>,
    ): Long? {
        if (currentIndex + 1 >= allLines.size) return null
        val nextLine = allLines[currentIndex + 1].trim()
        val match = RICH_SYNC_LINE_REGEX.matchEntire(nextLine) ?: return null
        val min = match.groupValues[1].toLongOrNull() ?: return null
        val sec = match.groupValues[2].toLongOrNull() ?: return null
        val frac = match.groupValues[3].toLongOrNull() ?: 0L
        val millisPart = if (match.groupValues[3].length == 3) frac else frac * 10
        return min * 60000 + sec * 1000 + millisPart
    }

    /**
     * Parse standard LRC format: [MM:SS.mm] text
     * Also supports Metrolist's word-data format where word timings are on the next line:
     * [MM:SS.mm] text
     * <word1:startTime:endTime|word2:startTime:endTime|...>
     */
    private fun parseStandardLyrics(lines: List<String>): List<LyricsEntry> {
        val result = mutableListOf<LyricsEntry>()

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()

            if (line.startsWith("<") && line.endsWith(">")) {
                i++
                continue
            }

            val timeMatchResults = TIME_REGEX.findAll(line).toList()
            if (timeMatchResults.isNotEmpty()) {
                val content = line.replace(TIME_REGEX, "").trim()
                if (content.isEmpty()) {
                    // A timed blank line is how LRC marks an instrumental break.
                    timeMatchResults.forEach { match -> result.add(LyricsEntry(standardLineTime(match), "")) }
                } else {
                    val wordTimestamps =
                        if (i + 1 < lines.size) {
                            val nextLine = lines[i + 1].trim()
                            if (nextLine.startsWith("<") && nextLine.endsWith(">")) {
                                parseMetrolistWordTimestamps(nextLine.removeSurrounding("<", ">"))
                            } else {
                                null
                            }
                        } else {
                            null
                        }

                    timeMatchResults.forEach { match ->
                        result.add(LyricsEntry(standardLineTime(match), content, wordTimestamps))
                    }
                }
            }
            i++
        }

        return result.takeIf { lines -> lines.any { it.text.isNotBlank() } }.orEmpty().sorted()
    }

    private fun standardLineTime(match: MatchResult): Long {
        val min = match.groupValues[1].toLong()
        val sec = match.groupValues[2].toLong()
        val msStr = match.groupValues[3]
        val ms = if (msStr.length == 2) msStr.toLong() * 10 else msStr.toLong()
        return min * 60000 + sec * 1000 + ms
    }

    /**
     * Parses Metrolist's word data format: word:startTime:endTime|word:startTime:endTime|...
     * Times are in seconds (Double).
     */
    private fun parseMetrolistWordTimestamps(data: String): List<WordTimestamp>? {
        if (data.isBlank()) return null
        return try {
            data.split("|").mapNotNull { wordData ->
                val parts = wordData.split(":")
                if (parts.size >= 3) {
                    val text = parts.dropLast(2).joinToString(":")
                    WordTimestamp(
                        text = text,
                        startTime = (parts[parts.size - 2].toDouble() * 1000).toLong(),
                        endTime = (parts[parts.size - 1].toDouble() * 1000).toLong(),
                    )
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Find the index of the current lyrics line based on playback position.
     */
    fun findCurrentLineIndex(
        lines: List<LyricsEntry>,
        position: Long,
    ): Int {
        for (index in lines.indices) {
            if (lines[index].time >= position + 300L) {
                return index - 1
            }
        }
        return lines.lastIndex
    }

    fun filterCreditLines(entries: List<LyricsEntry>): List<LyricsEntry> =
        entries.filter { entry ->
            val lower = entry.text.trim().lowercase()
            !(
                lower.startsWith("synced by") ||
                    lower.startsWith("lyrics by") ||
                    lower.startsWith("music by") ||
                    lower.startsWith("arranged by") ||
                    lower.startsWith("written by") ||
                    lower.startsWith("composed by")
            )
        }
}
