package io.github.aedev.flow.ui.screens.recap.story

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.stats.RecapPeriod
import io.github.aedev.flow.data.stats.RecapSummary
import io.github.aedev.flow.ui.components.layout.LocalFlowBottomInsets
import io.github.aedev.flow.ui.components.shared.FlowLoadingIndicator
import io.github.aedev.flow.ui.components.shared.LocalDpadNavigation
import io.github.aedev.flow.ui.components.shared.drawSegmentedProgress
import io.github.aedev.flow.ui.components.stats.spentTimeLabel
import io.github.aedev.flow.ui.screens.recap.RecapSource
import io.github.aedev.flow.ui.screens.recap.RecapViewModel
import kotlinx.coroutines.launch
import java.time.format.TextStyle
import java.util.Locale

private val EdgePadding = 20.dp
private val HeaderSpace = 72.dp
private val FooterSpace = 88.dp
private val IndicatorHeight = 4.dp
private val IndicatorGap = 4.dp
private val ControlSpacing = 12.dp
private const val PREVIOUS_ZONE = 0.3f

/**
 * The recap as a story that plays itself: each page runs for a few seconds, hold to pause, tap the
 * sides to step, swipe to skip. Only the page on screen is composed, and every animation on it
 * reads the story clock in the draw phase, so a paused or backgrounded story draws nothing.
 */
@Composable
internal fun RecapStoryScreen(
    period: RecapPeriod,
    source: RecapSource,
    onClose: () -> Unit,
    viewModel: RecapViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(period, source) { viewModel.openAt(period, source) }
    val summary = state.summary?.takeIf { it.period == period && state.source == source }
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.getDefault()

    if (summary == null) {
        Box(Modifier.fillMaxSize()) { FlowLoadingIndicator() }
    } else {
        StoryPager(summary, periodLabel(period, locale), locale, onClose)
    }
}

@Composable
private fun StoryPager(
    summary: RecapSummary,
    periodLabel: String,
    locale: Locale,
    onClose: () -> Unit,
) {
    val pages = remember(summary) { storyPages(summary) }
    val tints = rememberStoryTints(pages, summary)
    val pager = rememberPagerState { pages.size }
    val autoplay = rememberStoryAutoplay(pager)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val shareLayer = rememberGraphicsLayer()
    val shareTitle = stringResource(R.string.recap_story_share_title)
    val shareText = stringResource(R.string.recap_story_share_text, periodLabel, spentTimeLabel(summary.combined.totalMs))
    val nextLabel = stringResource(R.string.recap_story_next)
    val previousLabel = stringResource(R.string.recap_story_previous)
    val chrome by animateColorAsState(
        tints[pager.currentPage].onContainer,
        MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "storyChrome",
    )
    // The music mini player floats over the story; the pages and controls stay above it.
    val chromeAboveSystem = LocalFlowBottomInsets.current.chromeAboveSystem
    val step: (
        Int,
    ) -> Unit = { delta -> scope.launch { pager.animateScrollToPage((pager.currentPage + delta).coerceIn(0, pages.lastIndex)) } }

    Box(
        Modifier
            .fillMaxSize()
            .dpadPageKeys(step)
            .semantics {
                customActions =
                    listOf(
                        CustomAccessibilityAction(nextLabel) { step(1).let { true } },
                        CustomAccessibilityAction(previousLabel) { step(-1).let { true } },
                    )
            },
    ) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 0,
            key = { pages[it].toString() },
        ) { index ->
            val tint = tints[index]
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .storyBackdrop(tint.container, tint.onContainer, seed = index) { pageClock(pager, index, autoplay) }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    autoplay.held = true
                                    tryAwaitRelease()
                                    autoplay.held = false
                                },
                                onTap = { offset -> step(if (offset.x < size.width * PREVIOUS_ZONE) -1 else 1) },
                            )
                        }.safeDrawingPadding()
                        .padding(bottom = chromeAboveSystem)
                        .padding(start = EdgePadding, end = EdgePadding, top = HeaderSpace, bottom = FooterSpace),
                contentAlignment = Alignment.Center,
            ) {
                CompositionLocalProvider(LocalContentColor provides tint.onContainer) {
                    if (pages[index] == StoryPage.Summary) {
                        FittedShareCard { StoryShareCard(summary, periodLabel, tint, Modifier.captureInto(shareLayer)) }
                    } else {
                        Box(Modifier.verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                            StoryPageContent(pages[index], summary, periodLabel, tint, locale)
                        }
                    }
                }
            }
        }
        StoryHeader(pages.size, pager, autoplay, chrome, onClose, Modifier.align(Alignment.TopCenter))
        Row(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .safeDrawingPadding()
                    .padding(bottom = chromeAboveSystem)
                    .fillMaxWidth()
                    .padding(EdgePadding),
            horizontalArrangement = Arrangement.spacedBy(ControlSpacing, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val paused = autoplay.pausedByUser
            FilledTonalIconButton(onClick = { autoplay.pausedByUser = !paused }) {
                Icon(
                    if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                    contentDescription = stringResource(if (paused) R.string.recap_story_play else R.string.recap_story_pause),
                )
            }
            if (pager.currentPage == pages.lastIndex) {
                val tint = tints[pages.lastIndex]
                Button(
                    onClick = {
                        scope.launch {
                            val image = runCatching { shareLayer.toImageBitmap() }.getOrNull()
                            shareRecap(context, image, shareText, shareTitle)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = tint.accent, contentColor = tint.container),
                ) {
                    Icon(Icons.Outlined.Share, contentDescription = null)
                    Text(stringResource(R.string.recap_story_share), modifier = Modifier.padding(start = IndicatorGap * 2))
                }
            }
        }
    }
}

/** The page's own clock: running while it is the settled page, full once passed, empty ahead. */
private fun pageClock(
    pager: PagerState,
    index: Int,
    autoplay: StoryAutoplay,
): Float =
    when {
        index == pager.settledPage -> autoplay.progress
        index < pager.settledPage -> 1f
        else -> 0f
    }

@Composable
private fun StoryHeader(
    pageCount: Int,
    pager: PagerState,
    autoplay: StoryAutoplay,
    color: Color,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    val pageLabel = stringResource(R.string.recap_story_page, pager.currentPage + 1, pageCount)
    Row(
        modifier = modifier.safeDrawingPadding().fillMaxWidth().padding(horizontal = EdgePadding, vertical = ControlSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .weight(1f)
                .height(IndicatorHeight)
                .semantics { contentDescription = pageLabel }
                .drawSegmentedProgress(
                    count = pageCount,
                    color = color,
                    trackColor = color.copy(alpha = TRACK_ALPHA),
                    gap = IndicatorGap,
                ) { index -> pageClock(pager, index, autoplay) },
        )
        IconButton(onClick = onClose, colors = IconButtonDefaults.iconButtonColors(contentColor = color)) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.recap_story_close))
        }
    }
}

private const val TRACK_ALPHA = 0.3f

/**
 * Shows the fixed-size share poster scaled down to the space the page has. The scale is applied
 * around the poster, so the image recorded for sharing is always the full-size poster.
 */
@Composable
private fun FittedShareCard(card: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val scale = minOf(1f, maxWidth / ShareCardWidth, maxHeight / ShareCardHeight)
        Box(
            Modifier.requiredSize(ShareCardWidth, ShareCardHeight).graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        ) { card() }
    }
}

/** Draws the node as usual and also records it into [layer], so it can be shared as an image. */
private fun Modifier.captureInto(layer: GraphicsLayer): Modifier =
    drawWithContent {
        layer.record { this@drawWithContent.drawContent() }
        drawLayer(layer)
    }

/** "September 2026" for a month, "2026" for a year. */
internal fun periodLabel(
    period: RecapPeriod,
    locale: Locale,
): String =
    when (period) {
        is RecapPeriod.Month -> "${period.month.month.getDisplayName(TextStyle.FULL_STANDALONE, locale)} ${period.month.year}"
        is RecapPeriod.Year -> period.year.toString()
        RecapPeriod.AllTime -> ""
    }

/** A remote's Left and Right turn the page once no button lies that way, as a tap on either side does. */
@Composable
private fun Modifier.dpadPageKeys(step: (Int) -> Unit): Modifier {
    if (!LocalDpadNavigation.current) return this
    val focusManager = LocalFocusManager.current
    val forward = if (LocalLayoutDirection.current == LayoutDirection.Rtl) Key.DirectionLeft else Key.DirectionRight
    return onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
        val direction =
            when (event.key) {
                Key.DirectionLeft -> FocusDirection.Left
                Key.DirectionRight -> FocusDirection.Right
                else -> return@onKeyEvent false
            }
        if (!focusManager.moveFocus(direction)) step(if (event.key == forward) 1 else -1)
        true
    }
}
