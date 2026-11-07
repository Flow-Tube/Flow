package io.github.aedev.flow.ui.components.musicplayer.full

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.ui.components.musicplayer.lyrics.InlineLyricsPanel
import io.github.aedev.flow.ui.components.musicplayer.lyrics.LyricsDisplayOptions
import io.github.aedev.flow.ui.components.musicplayer.lyrics.MusicLyricsSheet
import io.github.aedev.flow.ui.components.musicplayer.lyrics.PANE_LYRICS_TEXT_SIZE
import io.github.aedev.flow.ui.components.musicplayer.lyrics.lyricsTextAlignFor
import io.github.aedev.flow.ui.screens.music.MusicPlayerUiState
import io.github.aedev.flow.ui.screens.music.MusicPlayerViewModel

/** The player's lyrics wired to its state: the full-screen sheet every layout can open. */
@Composable
internal fun NowPlayingLyricsSheet(
    uiState: MusicPlayerUiState,
    viewModel: MusicPlayerViewModel,
    visible: Boolean,
    retainContent: Boolean,
    backdropBaseColor: Color,
    accentColor: Color,
    fallbackTitle: String,
    fallbackArtist: String,
    artworkUrl: String,
    positionState: State<Long>,
    baseTextSize: Float,
    onDismiss: () -> Unit,
) {
    MusicLyricsSheet(
        visible = visible,
        retainContent = retainContent,
        backdropBaseColor = backdropBaseColor,
        accentColor = accentColor,
        trackTitle = uiState.currentTrack?.title ?: fallbackTitle,
        trackArtist = uiState.currentTrack?.artist ?: fallbackArtist,
        artworkUrl = artworkUrl,
        isPlaying = uiState.isPlaying,
        isBuffering = uiState.isBuffering,
        lyrics = uiState.lyrics,
        syncedLyrics = uiState.syncedLyrics,
        // Raw position — the panel's own sync loops apply syncOffsetMs; baking the
        // offset in here double-counted (and the loops ignored it anyway, #offset fix).
        positionProvider = { positionState.value },
        isLoading = uiState.isLyricsLoading,
        providerName = uiState.lyricsProviderName,
        alignPref = uiState.lyricsTextAlign,
        syncOffsetMs = uiState.lyricsSyncOffsetMs,
        display = uiState.lyricsDisplay(),
        baseTextSize = baseTextSize,
        candidates = uiState.lyricsCandidates,
        isBrowsing = uiState.isBrowsingLyrics,
        onSeekTo = { viewModel.seekTo((it - uiState.lyricsSyncOffsetMs).coerceAtLeast(0L)) },
        onRefresh = { viewModel.refreshLyrics() },
        onTogglePlayPause = { viewModel.togglePlayPause() },
        onAlignChange = { viewModel.setLyricsTextAlign(it) },
        onDisplayChange = { viewModel.applyLyricsDisplay(uiState.lyricsDisplay(), it) },
        onAdjustOffset = { viewModel.adjustLyricsSyncOffset(it) },
        onResetOffset = { viewModel.resetLyricsSyncOffset() },
        onBrowseSources = { viewModel.browseLyricsCandidates() }.takeUnless { LocalMediaIds.isLocal(uiState.currentTrack?.videoId) },
        onCancelBrowse = { viewModel.cancelLyricsBrowse() },
        onSelectCandidate = { viewModel.applyLyricsCandidate(it) },
        onApplyEditedLyrics = { viewModel.applyEditedLyrics(it) },
        onDismiss = onDismiss,
    )
}

/**
 * The lyrics in the wide player's side pane. [active] is false whenever nobody can see the pane
 * (player collapsed, or the full-screen sheet over it) so its sync loop and spinner stop.
 */
@Composable
internal fun NowPlayingLyricsPane(
    uiState: MusicPlayerUiState,
    viewModel: MusicPlayerViewModel,
    accentColor: Color,
    backdropColor: Color,
    positionState: State<Long>,
    active: Boolean,
) {
    InlineLyricsPanel(
        lyrics = uiState.lyrics,
        syncedLyrics = uiState.syncedLyrics,
        positionProvider = { positionState.value },
        isLoading = uiState.isLyricsLoading && active,
        accentColor = accentColor,
        onSeekTo = { viewModel.seekTo((it - uiState.lyricsSyncOffsetMs).coerceAtLeast(0L)) },
        providerName = uiState.lyricsProviderName,
        textAlign = lyricsTextAlignFor(uiState.lyricsTextAlign),
        syncOffsetMs = uiState.lyricsSyncOffsetMs,
        active = active,
        isPlaying = uiState.isPlaying,
        backdropColor = backdropColor,
        baseTextSize = PANE_LYRICS_TEXT_SIZE,
        display = uiState.lyricsDisplay(),
        modifier = Modifier.fillMaxSize(),
    )
}

private fun MusicPlayerUiState.lyricsDisplay() =
    LyricsDisplayOptions(
        showTranslation = lyricsShowTranslation,
        showRomanization = lyricsShowRomanization,
        autoRomanize = lyricsAutoRomanize,
        romanizationOnly = lyricsRomanizationOnly,
        keepScreenOn = lyricsKeepScreenOn,
        blurOtherLines = lyricsBlurOtherLines,
        background = lyricsBackground,
    )

private fun MusicPlayerViewModel.applyLyricsDisplay(
    old: LyricsDisplayOptions,
    new: LyricsDisplayOptions,
) {
    if (new.showTranslation != old.showTranslation) setLyricsShowTranslation(new.showTranslation)
    if (new.showRomanization != old.showRomanization) setLyricsShowRomanization(new.showRomanization)
    if (new.autoRomanize != old.autoRomanize) setLyricsAutoRomanize(new.autoRomanize)
    if (new.romanizationOnly != old.romanizationOnly) setLyricsRomanizationOnly(new.romanizationOnly)
    if (new.keepScreenOn != old.keepScreenOn) setLyricsKeepScreenOn(new.keepScreenOn)
    if (new.blurOtherLines != old.blurOtherLines) setLyricsBlurOtherLines(new.blurOtherLines)
    if (new.background != old.background) setLyricsBackground(new.background)
}
