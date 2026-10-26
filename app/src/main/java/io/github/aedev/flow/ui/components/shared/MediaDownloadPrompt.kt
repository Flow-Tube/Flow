package io.github.aedev.flow.ui.components.shared

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import io.github.aedev.flow.data.local.DownloadDialogStyle
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.VideoCodec
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.video.DownloadStreamPolicy
import io.github.aedev.flow.innertube.models.response.PlayerResponse.StreamingData.Format
import io.github.aedev.flow.player.state.SubtitleOption
import kotlinx.coroutines.flow.first

/**
 * What every Download button opens: the menu in the style chosen in settings, in [slot] when the
 * screen keeps one for its sheets. With Ask every time off nothing opens, and the choice the Classic
 * sheet would open on downloads at once (#1432).
 */
@Composable
fun MediaDownloadPrompt(
    streamSizes: Map<String, Long>,
    videoFormats: List<Format>,
    audioFormats: List<Format>,
    video: Video,
    onDismiss: () -> Unit,
    subtitles: List<SubtitleOption> = emptyList(),
    currentPlayingHeight: Int = 0,
    slot: MediaSheetSlot? = null,
) {
    val context = LocalContext.current
    val prefs = remember(context) { PlayerPreferences(context) }
    val style by prefs.downloadDialogStyle.collectAsState(initial = null)
    val askEveryTime by prefs.downloadAskEveryTime.collectAsState(initial = null)
    val latestOnDismiss by rememberUpdatedState(onDismiss)

    when {
        style == null || askEveryTime == null -> {
            Unit
        }

        askEveryTime == false -> {
            LaunchedEffect(video.id) {
                downloadDefaultChoice(context, prefs, video, videoFormats, audioFormats, subtitles)
                latestOnDismiss()
            }
        }

        style == DownloadDialogStyle.COMPACT -> {
            MediaDownloadSheetCompact(
                streamSizes = streamSizes,
                innerTubeVideoFormats = videoFormats,
                innerTubeAudioFormats = audioFormats,
                video = video,
                currentPlayingHeight = currentPlayingHeight,
                subtitles = subtitles,
                slot = slot,
                onDismiss = onDismiss,
            )
        }

        else -> {
            MediaDownloadSheet(
                streamSizes = streamSizes,
                innerTubeVideoFormats = videoFormats,
                innerTubeAudioFormats = audioFormats,
                video = video,
                subtitles = subtitles,
                slot = slot,
                onDismiss = onDismiss,
            )
        }
    }
}

/** The sheet's opening choice, downloaded without the sheet; with no video stream the queue picks. */
private suspend fun downloadDefaultChoice(
    context: Context,
    prefs: PlayerPreferences,
    video: Video,
    videoFormats: List<Format>,
    audioFormats: List<Format>,
    subtitles: List<SubtitleOption>,
) {
    val qualities = DownloadSheetChoices.qualities(DownloadStreamPolicy.buildDownloadVideoFormats(videoFormats))
    val quality = DownloadSheetChoices.preselect(qualities, prefs.defaultDownloadQuality.first().height)
    if (quality == null) {
        DownloadLauncher.startDefaultDownload(context, video)
        return
    }
    val codec =
        prefs.defaultDownloadCodec
            .first()
            .takeIf { it != VideoCodec.AUTO }
            ?.codecKey
    val format = DownloadSheetChoices.option(quality, current = null, preferredCodec = codec)?.let(quality::format)
    val subtitleOptions = downloadableSubtitles(subtitles)
    startVideo(
        context = context,
        video = video,
        format = format,
        audio =
            DownloadStreamPolicy.pickAacAudio(
                DownloadStreamPolicy.buildDownloadAudioFormats(audioFormats),
                prefs.preferredAudioLanguage.first(),
            ),
        subtitleOptions = subtitleOptions,
        subtitleChoice = defaultDownloadSubtitle(subtitleOptions, prefs.lastDownloadSubtitleLanguage.first()),
        prefs = prefs,
    )
}
