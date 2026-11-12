package io.github.aedev.flow.ui.components.musicplayer.lyrics

import io.github.aedev.flow.data.lyrics.LyricsEntry

internal const val MAX_SHARE_LINES = 5

/** The lines a card can be built from, in song order. */
internal fun shareableLyricLines(lines: List<LyricsEntry>): List<String> = lines.map { it.text.trim() }.filter { it.isNotBlank() }

/** Selects or deselects [index], keeping song order and refusing a line past [MAX_SHARE_LINES]. */
internal fun toggleShareLine(
    selected: List<Int>,
    index: Int,
): List<Int> =
    when {
        index in selected -> selected - index
        selected.size >= MAX_SHARE_LINES -> selected
        else -> (selected + index).sorted()
    }

/** The row of [shareableLyricLines] being sung at [positionMs], so the picker opens where the listener is. */
internal fun shareLineIndexAt(
    lines: List<LyricsEntry>,
    positionMs: Long,
): Int = (lines.count { it.text.isNotBlank() && it.time <= positionMs } - 1).coerceAtLeast(0)
