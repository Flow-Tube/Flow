package io.github.aedev.flow.ui.components.musicplayer.lyrics

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.lyrics.LyricsEntry
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.ui.theme.ensureContrastOn
import io.github.aedev.flow.ui.utils.fadingEdge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

private const val LYRICS_ANCHOR_RATIO = 0.35f
private val LYRICS_ITEM_FALLBACK_HEIGHT_DP = 68.dp
private val LYRICS_ITEM_GAP_DP = 16.dp
private val LYRICS_FADE_TOP_DP = 130.dp
private val LYRICS_FADE_BOTTOM_DP = 160.dp
private const val LYRICS_PREVIEW_TIME = 8000L

/** The main lyric size, in sp, before long lines are scaled down. */
internal const val DEFAULT_LYRICS_TEXT_SIZE = 36f

/** For the full-screen lyrics on a tablet, where the default reads small from arm's length. */
internal const val LARGE_LYRICS_TEXT_SIZE = 44f

/** For the wide player's side pane, which is narrower than a phone. */
internal const val PANE_LYRICS_TEXT_SIZE = 30f

@Composable
internal fun InlineLyricsPanel(
    lyrics: String?,
    syncedLyrics: List<LyricsEntry>,
    positionProvider: () -> Long,
    isLoading: Boolean,
    accentColor: Color,
    onSeekTo: (Long) -> Unit,
    providerName: String = "",
    textAlign: TextAlign = TextAlign.Center,
    // User-tuned lyric timing nudge. Applied INSIDE the sync loops (which track the raw
    // player position for smoothness), so adjustments take effect live mid-line.
    syncOffsetMs: Long = 0L,
    // False while the host keeps the panel composed but hidden: the 80 ms sync loop and the
    // per-frame karaoke interpolation stop, everything else stays warm for an instant reopen.
    active: Boolean = true,
    // Paused, the sync loop waits for a seek or an offset change instead of ticking every 80 ms.
    isPlaying: Boolean = true,
    // What the lyrics are drawn over: accents are lifted to read against it, and a duet's second
    // singer takes its hue. Null keeps the accent as given.
    backdropColor: Color? = null,
    baseTextSize: Float = DEFAULT_LYRICS_TEXT_SIZE,
    display: LyricsDisplayOptions = LyricsDisplayOptions(),
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    // A provider, not a value: the panel drives itself from the 80 ms interpolation loop below and
    // only needs the hosted position as a seed, so it must not recompose on every tick.
    val latestPositionProvider by rememberUpdatedState(positionProvider)
    val expressiveAccent =
        remember(accentColor, backdropColor) {
            backdropColor?.let { ensureContrastOn(accentColor, it, minRatio = 4.5f) } ?: accentColor
        }
    val motionEnabled = rememberLyricsMotionEnabled()

    val lines =
        remember(lyrics, syncedLyrics) {
            buildLines(lyrics = lyrics, syncedLyrics = syncedLyrics)
        }
    val mergedLyricsList = remember(lines) { buildMergedLyricsList(lines) }
    val hasWordTimings = remember(lines) { lines.any { !it.words.isNullOrEmpty() } }
    val isSynced =
        remember(lines, syncedLyrics) {
            syncedLyrics.isNotEmpty() && entriesLookSynced(syncedLyrics) &&
                lines.any { it.time in 1L..999_999L }
        }

    val state = remember { LyricsPanelState(positionProvider()) }
    val panelPosition = remember(state) { { state.currentPosition } }
    val romanized by produceState<Pair<List<LyricsEntry>, List<String?>>?>(null, lines, display.showRomanization, display.autoRomanize) {
        value = lines to withContext(Dispatchers.Default) { lyricsRomanizations(lines, display) }
    }
    val romanizations = romanized?.takeIf { it.first === lines }?.second.orEmpty()
    val presentation =
        remember(
            lines,
            accentColor,
            backdropColor,
            baseTextSize,
            textAlign,
            display.showTranslation,
            romanizations,
            display.romanizationOnly,
        ) {
            lyricsPresentation(
                lines = lines,
                accent = accentColor,
                backdrop = backdropColor,
                baseTextSize = baseTextSize,
                userAlign = textAlign,
                showTranslation = display.showTranslation,
                romanizations = romanizations,
                romanizationOnly = display.romanizationOnly,
            )
        }
    LaunchedEffect(lines) { state.reset() }

    val latestSyncOffsetMs by rememberUpdatedState(syncOffsetMs)
    LaunchedEffect(lines, active, isPlaying) {
        if (lines.isEmpty() || !active) return@LaunchedEffect
        if (!isPlaying) {
            snapshotFlow { latestPositionProvider() to latestSyncOffsetMs }.collect { (_, offset) ->
                val position = EnhancedMusicPlayerManager.getCurrentPosition() + offset
                state.advance(lines, position, hasWordTimings)
            }
            return@LaunchedEffect
        }

        var lastPlayerPos =
            EnhancedMusicPlayerManager.getCurrentPosition().takeIf { it > 0 }
                ?: latestPositionProvider()
        var lastUpdateTime = System.currentTimeMillis()
        state.startTracking(lastPlayerPos)

        while (isActive) {
            delay(80)
            val now = System.currentTimeMillis()
            val managerPosition = EnhancedMusicPlayerManager.getCurrentPosition()
            if (managerPosition != lastPlayerPos) {
                lastPlayerPos = managerPosition
                lastUpdateTime = now
            }
            val elapsed = now - lastUpdateTime
            val position =
                lastPlayerPos + (if (EnhancedMusicPlayerManager.isPlaying()) elapsed else 0L) + latestSyncOffsetMs

            state.advance(lines, position, hasWordTimings)
        }
    }

    LaunchedEffect(state.scrollTargetIndex, state.isAutoScrollEnabled) {
        if (state.scrollTargetIndex != -1 && state.isAutoScrollEnabled) {
            state.deferredCurrentLineIndex = state.scrollTargetIndex
        }
    }

    LaunchedEffect(state.isAutoScrollEnabled, state.lastPreviewTime) {
        if (!state.isAutoScrollEnabled && state.lastPreviewTime != 0L) {
            delay(LYRICS_PREVIEW_TIME)
            state.lastPreviewTime = 0L
            state.isAutoScrollEnabled = true
        }
    }

    BoxWithConstraints(
        contentAlignment = Alignment.TopCenter,
        modifier =
            modifier
                .fillMaxSize()
                .then(if (display.keepScreenOn && active && isPlaying) Modifier.keepScreenOn() else Modifier),
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = expressiveAccent,
                modifier = Modifier.align(Alignment.Center),
            )
            return@BoxWithConstraints
        }

        if (lines.isEmpty()) {
            Text(
                text =
                    androidx.compose.ui.res
                        .stringResource(R.string.lyrics_not_available),
                color = Color.White.copy(alpha = 0.58f),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.align(Alignment.Center),
            )
            return@BoxWithConstraints
        }

        val maxHeightPx = constraints.maxHeight.toFloat()
        val anchorY = maxHeightPx * LYRICS_ANCHOR_RATIO
        val itemHeights = remember(lines, mergedLyricsList) { mutableStateMapOf<Int, Int>() }
        var isInitialLayout by remember(lines, mergedLyricsList) { mutableStateOf(true) }
        var flingJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
        val velocityTracker = remember { VelocityTracker() }
        val decayAnimSpec = remember { exponentialDecay<Float>(frictionMultiplier = 1.8f) }
        val activeListIndex by remember(mergedLyricsList, state.deferredCurrentLineIndex) {
            derivedStateOf {
                mergedLyricsList
                    .indexOfFirst {
                        (it is LyricsListItem.Line && it.index == state.deferredCurrentLineIndex) ||
                            (it is LyricsListItem.Indicator && it.afterLineIndex == state.deferredCurrentLineIndex)
                    }.coerceAtLeast(0)
            }
        }

        val metrics =
            remember(density) {
                with(density) {
                    LyricsRowMetrics(
                        lineHeightPx = LYRICS_ITEM_FALLBACK_HEIGHT_DP.toPx(),
                        indicatorHeightPx = 72.dp.toPx(),
                        constraintLineHeightPx = 120.dp.toPx(),
                        gapPx = LYRICS_ITEM_GAP_DP.toPx(),
                    )
                }
            }
        val positions =
            remember(itemHeights.toMap(), activeListIndex, mergedLyricsList) {
                lyricsRowOffsets(mergedLyricsList, itemHeights, activeListIndex, metrics)
            }
        val minOffset =
            remember(itemHeights.toMap(), mergedLyricsList, activeListIndex, anchorY) {
                lyricsMinScrollOffset(
                    items = mergedLyricsList,
                    heights = itemHeights,
                    activeIndex = activeListIndex,
                    anchorY = anchorY,
                    bottomMarginPx = with(density) { 100.dp.toPx() },
                    metrics = metrics,
                )
            }
        val maxOffset =
            remember(itemHeights.toMap(), mergedLyricsList, activeListIndex, maxHeightPx, anchorY) {
                lyricsMaxScrollOffset(
                    items = mergedLyricsList,
                    heights = itemHeights,
                    activeIndex = activeListIndex,
                    anchorY = anchorY,
                    viewportHeightPx = maxHeightPx,
                    topMarginPx = with(density) { 150.dp.toPx() },
                    metrics = metrics,
                )
            }

        val scrollClampMin = minOf(minOffset, maxOffset)
        val scrollClampMax = maxOf(minOffset, maxOffset)

        val resyncToCurrentLine = {
            flingJob?.cancel()
            val target = mainLineAt(lines, state.currentPosition) ?: state.scrollTargetIndex
            if (target != -1) {
                val listIndex =
                    mergedLyricsList
                        .indexOfFirst {
                            it is LyricsListItem.Line && it.index == target
                        }.coerceAtLeast(0)
                state.userManualOffset += positions[listIndex] ?: 0f
            }
            state.resync(lines)
        }

        LaunchedEffect(scrollClampMin, scrollClampMax) {
            state.userManualOffset = state.userManualOffset.coerceIn(scrollClampMin, scrollClampMax)
        }

        LaunchedEffect(state.isAutoScrollEnabled, lines) {
            if (state.isAutoScrollEnabled) {
                val start = state.userManualOffset
                if (abs(start) < 1f) {
                    state.userManualOffset = 0f
                    return@LaunchedEffect
                }
                val anim = Animatable(start)
                var lastValue = start
                anim.animateTo(
                    targetValue = 0f,
                    animationSpec = tween((abs(start) / 4f).toInt().coerceIn(200, 600), easing = FastOutSlowInEasing),
                ) {
                    state.userManualOffset += value - lastValue
                    lastValue = value
                }
                state.userManualOffset = 0f
            }
        }

        LaunchedEffect(lines, mergedLyricsList.size, activeListIndex) {
            if (mergedLyricsList.isNotEmpty()) {
                isInitialLayout = true
                snapshotFlow {
                    val h = itemHeights.toMap()
                    val windowStart = (activeListIndex - 8).coerceAtLeast(0)
                    val windowEnd = (activeListIndex + 12).coerceAtMost(mergedLyricsList.size - 1)
                    (windowStart..windowEnd).all { h.containsKey(it) }
                }.first { it }
                isInitialLayout = false
            }
        }

        if (isSynced && active && isPlaying && state.isAutoScrollEnabled) {
            CurrentLineAnnouncer { lines.getOrNull(state.deferredCurrentLineIndex)?.text.orEmpty() }
        }

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .fadingEdge(top = LYRICS_FADE_TOP_DP, bottom = LYRICS_FADE_BOTTOM_DP)
                    .clipToBounds()
                    .pointerInput(scrollClampMin, scrollClampMax, isInitialLayout) {
                        val touchSlop = viewConfiguration.touchSlop
                        awaitPointerEventScope {
                            while (true) {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                if (isInitialLayout) continue
                                flingJob?.cancel()
                                velocityTracker.resetTracking()
                                velocityTracker.addPosition(down.uptimeMillis, down.position)

                                var totalDrag = 0f
                                var isDragging = false

                                verticalDrag(down.id) { change ->
                                    val delta = change.positionChange().y
                                    totalDrag += abs(delta)

                                    if (!isDragging && totalDrag > touchSlop) {
                                        isDragging = true
                                    }

                                    if (isDragging) {
                                        val next = (state.userManualOffset + delta).coerceIn(scrollClampMin, scrollClampMax)
                                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                                        if (next != state.userManualOffset) {
                                            state.onUserScroll(next, System.currentTimeMillis())
                                            change.consume()
                                        }
                                    }
                                }

                                if (isDragging) {
                                    val velocity = velocityTracker.calculateVelocity().y
                                    flingJob =
                                        scope.launch {
                                            AnimationState(initialValue = state.userManualOffset, initialVelocity = velocity)
                                                .animateDecay(decayAnimSpec) {
                                                    val clamped = value.coerceIn(scrollClampMin, scrollClampMax)
                                                    state.userManualOffset = clamped
                                                    if (value != clamped) cancelAnimation()
                                                }
                                        }
                                }
                            }
                        }
                    },
        ) {
            val renderPaddingPx = with(density) { 420.dp.toPx() }
            mergedLyricsList.forEachIndexed { listIndex, listItem ->
                val estimatedOffset =
                    anchorY +
                        positions.getOrDefault(
                            listIndex,
                            (listIndex - activeListIndex) * metrics.lineHeightPx,
                        ) + state.userManualOffset
                val shouldRender =
                    (
                        estimatedOffset > -renderPaddingPx &&
                            estimatedOffset < maxHeightPx + renderPaddingPx
                    ) ||
                        abs(listIndex - activeListIndex) <= 14
                if (!shouldRender) return@forEachIndexed

                key(listItem) {
                    LyricsListRow(
                        listItem = listItem,
                        distance = abs(listIndex - activeListIndex),
                        targetOffset = anchorY + positions.getOrDefault(listIndex, (listIndex - activeListIndex) * metrics.lineHeightPx),
                        isInitialLayout = isInitialLayout,
                        state = state,
                        lines = lines,
                        presentation = presentation,
                        isSynced = isSynced,
                        active = active,
                        isPlaying = isPlaying,
                        syncOffsetMs = latestSyncOffsetMs,
                        motionEnabled = motionEnabled,
                        blurOtherLines = display.blurOtherLines,
                        indicatorColor = expressiveAccent,
                        positionProvider = panelPosition,
                        onHeight = { itemHeights[listIndex] = it },
                        onLineClick = { index, item ->
                            if (isSynced) {
                                val seekTarget = item.time.coerceAtLeast(0L)
                                val duration = EnhancedMusicPlayerManager.getDuration()
                                if (duration <= 0L || seekTarget < duration + 30_000L) {
                                    onSeekTo(seekTarget)
                                }
                                state.jumpTo(index)
                            }
                        },
                    )
                }
            }
        }

        LyricsResyncButton(
            visible = !state.isAutoScrollEnabled && isSynced,
            accent = expressiveAccent,
            onClick = resyncToCurrentLine,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
        )
        LyricsProviderLabel(
            providerName = providerName,
            accent = expressiveAccent,
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 38.dp),
        )
    }
}

/**
 * Reads each new line to TalkBack as it becomes current. A polite live region waits for the user
 * to finish what they are doing, and the reader only recomposes this node on a line change.
 */
@Composable
private fun CurrentLineAnnouncer(currentLine: () -> String) {
    val text by remember { derivedStateOf(currentLine) }
    Box(
        modifier =
            Modifier
                .size(1.dp)
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = text
                },
    )
}
