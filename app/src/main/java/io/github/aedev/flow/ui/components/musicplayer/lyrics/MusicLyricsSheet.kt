package io.github.aedev.flow.ui.components.musicplayer.lyrics

import android.content.ClipData
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.LyricsBackgroundStyle
import io.github.aedev.flow.data.lyrics.LyricsCandidate
import io.github.aedev.flow.data.lyrics.LyricsEntry
import io.github.aedev.flow.data.lyrics.lrcText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Full-screen lyrics surface presented over the expanded player. Show/hide is a manual
 * graphicsLayer animation over content that stays composed while [retainContent] holds, so only
 * the very first open pays the lyrics renderer's composition cost — and even that is deferred
 * past the slide so the transition itself never drops frames. When fully hidden the sheet is
 * parked off-screen, which also keeps it out of hit testing.
 */
@Composable
internal fun MusicLyricsSheet(
    visible: Boolean,
    retainContent: Boolean,
    backdropBaseColor: Color,
    accentColor: Color,
    trackTitle: String,
    trackArtist: String,
    artworkUrl: String,
    isPlaying: Boolean,
    isBuffering: Boolean,
    lyrics: String?,
    syncedLyrics: List<LyricsEntry>,
    positionProvider: () -> Long,
    isLoading: Boolean,
    providerName: String,
    alignPref: String,
    syncOffsetMs: Long,
    display: LyricsDisplayOptions,
    baseTextSize: Float,
    candidates: List<LyricsCandidate>,
    isBrowsing: Boolean,
    onSeekTo: (Long) -> Unit,
    onRefresh: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onAlignChange: (String) -> Unit,
    onDisplayChange: (LyricsDisplayOptions) -> Unit,
    onAdjustOffset: (Long) -> Unit,
    onResetOffset: () -> Unit,
    /** Null for a device song, whose lyrics only ever come from its own file. */
    onBrowseSources: (() -> Unit)?,
    onCancelBrowse: () -> Unit,
    onSelectCandidate: (LyricsCandidate) -> Unit,
    onApplyEditedLyrics: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetShown = remember { Animatable(0f) }
    val backProgress = remember { Animatable(0f) }
    var panelComposed by remember { mutableStateOf(false) }
    var showActionsSheet by remember { mutableStateOf(false) }
    var showSourcesSheet by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showSyncControls by remember { mutableStateOf(false) }
    var pendingSaveText by remember { mutableStateOf<String?>(null) }
    val visibleState = rememberUpdatedState(visible)
    val onDismissState = rememberUpdatedState(onDismiss)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current

    LaunchedEffect(visible) {
        if (visible) {
            launch {
                sheetShown.animateTo(
                    targetValue = 1f,
                    animationSpec =
                        spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                )
            }
            delay(140)
            panelComposed = true
        } else {
            showActionsSheet = false
            showSourcesSheet = false
            showEditDialog = false
            showSyncControls = false
            sheetShown.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
            )
            backProgress.snapTo(0f)
        }
    }

    LaunchedEffect(retainContent) {
        if (!retainContent) panelComposed = false
    }

    PredictiveBackHandler(enabled = visible) { progressFlow ->
        try {
            progressFlow.collect { backEvent ->
                backProgress.snapTo(backEvent.progress)
            }
            backProgress.animateTo(1f, tween(durationMillis = 160))
            onDismissState.value()
        } catch (e: CancellationException) {
            scope.launch {
                backProgress.animateTo(0f, tween(durationMillis = 250))
            }
            throw e
        }
    }

    val saveLauncher =
        rememberLauncherForActivityResult(
            // LRC is UTF-8 text; a text MIME keeps document providers, previews and
            // share targets treating the export as the plain text it is.
            ActivityResultContracts.CreateDocument("text/plain"),
        ) { uri ->
            val text = pendingSaveText
            pendingSaveText = null
            if (uri != null && text != null) {
                scope.launch(Dispatchers.IO) {
                    val ok =
                        runCatching {
                            context.contentResolver.openOutputStream(uri)?.use { out ->
                                out.write(text.toByteArray(Charsets.UTF_8))
                            } != null
                        }.getOrDefault(false)
                    withContext(Dispatchers.Main) {
                        Toast
                            .makeText(
                                context,
                                context.getString(if (ok) R.string.lyrics_saved else R.string.lyrics_save_failed),
                                Toast.LENGTH_SHORT,
                            ).show()
                    }
                }
            }
        }

    val panelAlpha by animateFloatAsState(
        targetValue = if (panelComposed) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "lyricsPanelAlpha",
    )

    val sheetAtRestHidden by remember { derivedStateOf { !sheetShown.isRunning && sheetShown.value == 0f } }
    if (!visible && !panelComposed && sheetAtRestHidden) return

    val usesCover = display.background != LyricsBackgroundStyle.SOLID
    val cover = rememberLyricsCover(artworkUrl, enabled = usesCover && visible)
    val backdropColor =
        remember(backdropBaseColor, cover, usesCover) {
            cover?.backdrop?.takeIf { usesCover } ?: lyricsBackdrop(backdropBaseColor)
        }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .graphicsLayer {
                    val shown = sheetShown.value
                    val back = backProgress.value
                    if (shown <= 0.001f && !visibleState.value) {
                        alpha = 0f
                        translationY = size.height
                    } else {
                        alpha = (shown * (1f - back * 0.25f)).coerceIn(0f, 1f)
                        translationY = (1f - shown) * size.height / 5f + back * size.height * 0.06f
                    }
                }.background(backdropColor),
    ) {
        if (usesCover) {
            LyricsCoverLayer(
                cover = cover,
                moving = display.background == LyricsBackgroundStyle.MOVING,
                running = visible && isPlaying,
            )
        }
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(top = 8.dp, start = 24.dp, end = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                LyricsTrackPill(
                    title = trackTitle,
                    artist = trackArtist,
                    artworkUrl = artworkUrl,
                    // The sheet stays composed at alpha 0 while retained — neither the pill's
                    // waveform nor its loading indicator may animate behind an invisible layer.
                    isPlaying = isPlaying && visible,
                    isLoading = isLoading && visible,
                )
            }

            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
            ) {
                if (panelComposed) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer { alpha = panelAlpha },
                    ) {
                        InlineLyricsPanel(
                            lyrics = lyrics,
                            syncedLyrics = syncedLyrics,
                            positionProvider = positionProvider,
                            isLoading = isLoading && visible,
                            accentColor = accentColor,
                            onSeekTo = onSeekTo,
                            providerName = providerName,
                            textAlign = lyricsTextAlignFor(alignPref),
                            syncOffsetMs = syncOffsetMs,
                            // Retention keeps the panel composed for an instant reopen; the
                            // position loops must still pause while the sheet is hidden.
                            active = visible,
                            isPlaying = isPlaying,
                            backdropColor = backdropColor,
                            baseTextSize = baseTextSize,
                            display = display,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = showSyncControls,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                LyricsSyncOffsetRow(
                    offsetMs = syncOffsetMs,
                    onAdjust = onAdjustOffset,
                    onReset = onResetOffset,
                    onHide = { showSyncControls = false },
                    modifier =
                        Modifier
                            .padding(horizontal = 20.dp)
                            .padding(bottom = 10.dp),
                )
            }

            LyricsBottomBar(
                isPlaying = isPlaying,
                isBuffering = isBuffering,
                onBack = { onDismissState.value() },
                onTogglePlayPause = onTogglePlayPause,
                onMenu = { showActionsSheet = true },
                modifier =
                    Modifier
                        .padding(horizontal = 20.dp)
                        .navigationBarsPadding()
                        .padding(bottom = 8.dp),
            )
        }
    }

    if (showActionsSheet) {
        LyricsActionsSheet(
            hasLyrics = !lyrics.isNullOrBlank() || syncedLyrics.isNotEmpty(),
            providerName = providerName,
            alignPref = alignPref,
            syncOffsetMs = syncOffsetMs,
            display = display,
            japaneseLyrics =
                remember(lyrics, syncedLyrics) {
                    isJapaneseLyrics(syncedLyrics.map { it.text }.ifEmpty { lyrics?.lines().orEmpty() })
                },
            hasTranslation = remember(syncedLyrics) { syncedLyrics.any { !it.translation.isNullOrBlank() } },
            onRefresh = onRefresh,
            onChooseSource =
                onBrowseSources?.let { browse ->
                    {
                        showSourcesSheet = true
                        browse()
                    }
                },
            onEdit = { showEditDialog = true },
            onCopy = {
                val text = lyrics ?: syncedLyrics.joinToString("\n") { it.text }
                if (text.isNotBlank()) {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(trackTitle, text)))
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            Toast
                                .makeText(context, R.string.lyrics_copied, Toast.LENGTH_SHORT)
                                .show()
                        }
                    }
                }
            },
            onSaveFile = {
                pendingSaveText = lrcText(syncedLyrics, lyrics)
                val baseName =
                    listOf(trackArtist, trackTitle)
                        .filter { it.isNotBlank() }
                        .joinToString(" - ")
                        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                saveLauncher.launch("$baseName.lrc")
            },
            onAlignChange = onAlignChange,
            onDisplayChange = onDisplayChange,
            onAdjustSync = { showSyncControls = true },
            onDismiss = { showActionsSheet = false },
        )
    }

    if (showSourcesSheet) {
        LyricsSourcesSheet(
            candidates = candidates,
            isBrowsing = isBrowsing,
            currentProviderName = providerName,
            onSelect = {
                onSelectCandidate(it)
                showSourcesSheet = false
            },
            onDismiss = {
                onCancelBrowse()
                showSourcesSheet = false
            },
        )
    }

    if (showEditDialog) {
        LyricsEditDialog(
            initialText = lrcText(syncedLyrics, lyrics),
            onApply = onApplyEditedLyrics,
            onDismiss = { showEditDialog = false },
        )
    }
}
