package io.github.aedev.flow.ui.components.videoplayer.sheet

import io.github.aedev.flow.data.transcript.TranscriptCue
import io.github.aedev.flow.utils.formatDurationMillis

/**
 * The whole transcript as plain text, one line per spoken line, with each chapter's title as a
 * heading after a blank line. Timestamps read the same as on screen, so `[1:02:15]` past an hour.
 */
internal fun transcriptText(
    cues: List<TranscriptCue>,
    chapters: List<TranscriptChapter>,
    withTimestamps: Boolean,
): String =
    buildString {
        transcriptItems(cues, chapters).forEach { item ->
            when (item) {
                is TranscriptItem.Heading -> {
                    if (isNotEmpty()) append('\n')
                    append(item.chapter.title.trim()).append('\n')
                }

                is TranscriptItem.Line -> {
                    if (withTimestamps) append('[').append(formatDurationMillis(item.cue.startMs)).append("] ")
                    append(item.cue.text.trim()).append('\n')
                }
            }
        }
    }.trimEnd()
