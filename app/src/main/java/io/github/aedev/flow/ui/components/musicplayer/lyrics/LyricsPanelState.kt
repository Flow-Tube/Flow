package io.github.aedev.flow.ui.components.musicplayer.lyrics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.aedev.flow.data.lyrics.LyricsEntry

/**
 * Which lines are sung and which line the panel scrolls to. [advance] runs once per sync tick with
 * the offset-adjusted position; everything the panel draws is read from the snapshot fields.
 */
@Stable
internal class LyricsPanelState(
    initialPosition: Long,
) {
    var activeLineIndices by mutableStateOf(emptySet<Int>())
        private set
    var scrollTargetIndex by mutableIntStateOf(-1)
        private set
    var currentPosition by mutableLongStateOf(initialPosition)
        private set
    var deferredCurrentLineIndex by mutableIntStateOf(0)
    var lastPreviewTime by mutableLongStateOf(0L)
    var isAutoScrollEnabled by mutableStateOf(true)
    var userManualOffset by mutableFloatStateOf(0f)

    private var previousScrollActiveIndices = emptySet<Int>()
    private var lastMainMaxSeen = -1
    private var previousPosition = initialPosition

    fun reset() {
        activeLineIndices = emptySet()
        previousScrollActiveIndices = emptySet()
        scrollTargetIndex = -1
        deferredCurrentLineIndex = 0
        isAutoScrollEnabled = true
        userManualOffset = 0f
        lastMainMaxSeen = -1
    }

    fun startTracking(position: Long) {
        previousPosition = position
    }

    /** A tap on a synced line: follow that line from now on. */
    fun jumpTo(index: Int) {
        scrollTargetIndex = index
        deferredCurrentLineIndex = index
        lastMainMaxSeen = index
        isAutoScrollEnabled = true
        lastPreviewTime = 0L
    }

    fun onUserScroll(
        offset: Float,
        nowMs: Long,
    ) {
        userManualOffset = offset
        isAutoScrollEnabled = false
        lastPreviewTime = nowMs
    }

    fun advance(
        lines: List<LyricsEntry>,
        position: Long,
        hasWordTimings: Boolean,
    ) {
        if (previousPosition - position > 2000L && isAutoScrollEnabled) {
            val seekTarget = mainLineAt(lines, position) ?: 0
            scrollTargetIndex = seekTarget
            lastMainMaxSeen = seekTarget
            deferredCurrentLineIndex = seekTarget
        }
        previousPosition = position
        currentPosition = position

        val initialActiveIndices = findActiveLineIndices(lines, position)
        val scrollActiveRaw = findActiveLineIndices(lines, position + if (hasWordTimings) 0L else 250L)
        val scrollActiveIndices = withMainLinesOfBackground(lines, scrollActiveRaw)
        val newActiveIndices = withMainLinesOfBackground(lines, initialActiveIndices)

        val scrollMax =
            scrollActiveIndices
                .filter { lines.getOrNull(it)?.isBackground == false }
                .maxOrNull() ?: (scrollActiveIndices.maxOrNull() ?: -1)

        val targetOutsideActive = scrollTargetIndex !in scrollActiveIndices

        val shouldScroll =
            when {
                targetOutsideActive && scrollActiveIndices.isNotEmpty() && scrollMax > scrollTargetIndex -> true
                targetOutsideActive && scrollActiveIndices.isEmpty() && previousScrollActiveIndices.isNotEmpty() -> true
                scrollTargetIndex == -1 && scrollActiveIndices.isNotEmpty() -> true
                previousScrollActiveIndices.isEmpty() && scrollActiveIndices.isNotEmpty() && scrollMax > scrollTargetIndex -> true
                else -> false
            }

        if (shouldScroll) {
            val targetToScroll =
                when {
                    scrollTargetIndex !in scrollActiveIndices && scrollActiveIndices.isNotEmpty() -> {
                        scrollMax
                    }

                    scrollTargetIndex !in scrollActiveIndices && scrollActiveIndices.isEmpty() -> {
                        (lastMainMaxSeen + 1 until lines.size).firstOrNull {
                            lines.getOrNull(it)?.isBackground == false
                        } ?: scrollTargetIndex
                    }

                    else -> {
                        scrollMax
                    }
                }
            if (targetToScroll != -1 && targetToScroll > scrollTargetIndex) {
                scrollTargetIndex = targetToScroll
            }
        }

        // Nothing is sung during a break, so a panel opened or moved into one settles on the line coming up.
        if (scrollActiveIndices.isEmpty()) {
            val upcoming = lines.indexOfFirst { !it.isBackground && it.time > position }
            if (upcoming > scrollTargetIndex) {
                scrollTargetIndex = upcoming
                lastMainMaxSeen = upcoming - 1
            }
        }

        if (scrollMax > lastMainMaxSeen && scrollMax != -1) lastMainMaxSeen = scrollMax
        previousScrollActiveIndices = scrollActiveIndices
        activeLineIndices = newActiveIndices
    }

    /** Snaps back to the line being sung now, after the user scrolled away. */
    fun resync(lines: List<LyricsEntry>) {
        val target = mainLineAt(lines, currentPosition) ?: scrollTargetIndex
        if (target != -1) {
            deferredCurrentLineIndex = target
            scrollTargetIndex = target
            lastMainMaxSeen = target
        }
        isAutoScrollEnabled = true
        lastPreviewTime = 0L
    }
}

/** The last main (not background) line active at [position], else the last active line. */
internal fun mainLineAt(
    lines: List<LyricsEntry>,
    position: Long,
): Int? {
    val active = findActiveLineIndices(lines, position)
    return active.filter { lines.getOrNull(it)?.isBackground == false }.maxOrNull() ?: active.maxOrNull()
}

/** A background vocal line keeps the main line it belongs to active alongside it. */
internal fun withMainLinesOfBackground(
    lines: List<LyricsEntry>,
    indices: Set<Int>,
): Set<Int> {
    val result = indices.toMutableSet()
    for (i in indices) {
        if (lines.getOrNull(i)?.isBackground == true) {
            for (j in i - 1 downTo 0) {
                if (lines.getOrNull(j)?.isBackground == false) {
                    result.add(j)
                    break
                }
            }
        }
    }
    return result
}
