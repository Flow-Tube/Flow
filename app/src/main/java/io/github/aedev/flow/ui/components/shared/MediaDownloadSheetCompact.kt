package io.github.aedev.flow.ui.components.shared

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.VideoCodec
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.video.DownloadStreamPolicy
import io.github.aedev.flow.data.video.downloader.request.DownloadSubtitle
import io.github.aedev.flow.innertube.models.response.PlayerResponse
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.state.SubtitleOption
import io.github.aedev.flow.player.stream.VideoCodecUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val MIN_THREADS = 1
private const val MAX_THREADS = 8
private val downloadPrefsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private const val UNRANKED_CODEC = 99

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun MediaDownloadSheetCompact(
    streamSizes: Map<String, Long>,
    innerTubeVideoFormats: List<PlayerResponse.StreamingData.Format> = emptyList(),
    innerTubeAudioFormats: List<PlayerResponse.StreamingData.Format> = emptyList(),
    video: Video,
    currentPlayingHeight: Int = 0,
    subtitles: List<SubtitleOption> = emptyList(),
    slot: MediaSheetSlot? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val separatorDot = stringResource(R.string.list_separator_dot)
    val hdrLabel = stringResource(R.string.download_quality_hdr)
    val audioLabelStrings =
        AudioLabelStrings(
            unknownFormat = stringResource(R.string.audio_format_unknown),
            kbps = stringResource(R.string.kbps),
            separator = separatorDot,
        )
    val prefs = remember(context) { PlayerPreferences(context) }
    val preferredLang by prefs.preferredAudioLanguage.collectAsState(initial = "")
    val defaultThreads by prefs.downloadThreads.collectAsState(initial = 3)
    val lastType by prefs.lastDownloadType.collectAsState(initial = null)
    val lastHeight by prefs.lastDownloadHeight.collectAsState(initial = null)
    val lastCodec by prefs.lastDownloadCodec.collectAsState(initial = null)
    val lastAudioLabel by prefs.lastDownloadAudioLabel.collectAsState(initial = null)
    val lastSubtitleLanguage by prefs.lastDownloadSubtitleLanguage.collectAsState(initial = null)
    val subtitleOptions = remember(subtitles) { downloadableSubtitles(subtitles) }
    var subtitleChoice by remember(subtitleOptions, lastSubtitleLanguage) {
        mutableStateOf(defaultDownloadSubtitle(subtitleOptions, lastSubtitleLanguage))
    }
    val defaultDownloadCodec by prefs.defaultDownloadCodec.collectAsState(initial = VideoCodec.AUTO)
    val preferredDownloadCodecKey = defaultDownloadCodec.takeIf { it != VideoCodec.AUTO }?.codecKey

    val currentPlayingCodec =
        remember {
            EnhancedPlayerManager
                .getInstance()
                .getPlayer()
                ?.videoFormat
                ?.sampleMimeType
                ?.let { VideoCodecUtils.codecKeyFromMimeType(it) }
        }

    val videoStreams =
        remember(innerTubeVideoFormats) { DownloadStreamPolicy.buildDownloadVideoFormats(innerTubeVideoFormats) }
    val audioStreams =
        remember(innerTubeAudioFormats) { DownloadStreamPolicy.buildDownloadAudioFormats(innerTubeAudioFormats) }
    val heights =
        remember(videoStreams) {
            videoStreams.map(DownloadStreamPolicy::videoHeight).distinct().sortedDescending()
        }

    val hasVideo = videoStreams.isNotEmpty()
    val hasAudio = audioStreams.isNotEmpty()

    var title by remember(video.id) { mutableStateOf(video.title) }
    var threads by remember(defaultThreads) { mutableStateOf(defaultThreads.coerceIn(MIN_THREADS, MAX_THREADS)) }

    var isAudioMode by remember(lastType, hasVideo, hasAudio) {
        mutableStateOf((lastType == "AUDIO" && hasAudio) || !hasVideo)
    }
    var selectedHeight by remember(heights, lastHeight) {
        mutableStateOf(
            heights.firstOrNull { it == lastHeight }
                ?: heights.firstOrNull { it == currentPlayingHeight }
                ?: heights.firstOrNull() ?: 0,
        )
    }
    val codecsForHeight =
        videoStreams
            .filter { DownloadStreamPolicy.videoHeight(it) == selectedHeight }
            .map(DownloadStreamPolicy::videoCodecKey)
            .distinct()
            .sortedBy { DownloadStreamPolicy.DOWNLOAD_CODEC_PRIORITY[it] ?: UNRANKED_CODEC }
    var selectedCodec by remember(selectedHeight, lastCodec, preferredDownloadCodecKey) {
        mutableStateOf(
            codecsForHeight.firstOrNull { it == preferredDownloadCodecKey }
                ?: codecsForHeight.firstOrNull { it == lastCodec }
                ?: codecsForHeight.firstOrNull { it == currentPlayingCodec }
                ?: codecsForHeight.firstOrNull() ?: "",
        )
    }
    var selectedAudioIndex by remember(audioStreams, lastAudioLabel, preferredLang) {
        mutableStateOf(
            DownloadStreamPolicy.defaultAudioIndex(audioStreams, preferredLang, lastAudioLabel) {
                audioOptionLabel(it, audioLabelStrings)
            },
        )
    }

    val audioTracks = remember(audioStreams) { DownloadStreamPolicy.audioTrackChoices(audioStreams) }
    var videoAudio by remember(audioStreams, preferredLang) {
        mutableStateOf(DownloadStreamPolicy.pickAacAudio(audioStreams, preferredLang))
    }

    val selectedSizeText = approxDownloadSizeLabel(streamSizes[VideoCodecUtils.streamSizeKey(selectedHeight, selectedCodec)])

    fun confirmDownload(): Boolean {
        val finalTitle = title.trim().ifBlank { video.title }
        val taggedVideo = video.copy(title = finalTitle)
        if (isAudioMode) {
            val stream = audioStreams.getOrNull(selectedAudioIndex) ?: return false
            if (!DownloadLauncher.startAudioOnlyDownload(context, taggedVideo, stream, threads)) return false
            Toast
                .makeText(
                    context,
                    context.getString(R.string.downloading_template, audioOptionLabel(stream, audioLabelStrings)),
                    Toast.LENGTH_SHORT,
                ).show()
            downloadPrefsScope.launch {
                prefs.setLastDownloadAudioChoice(audioOptionLabel(stream, audioLabelStrings))
                prefs.setDownloadThreads(threads)
            }
            return true
        }

        val stream =
            videoStreams.firstOrNull {
                DownloadStreamPolicy.videoHeight(it) == selectedHeight &&
                    DownloadStreamPolicy.videoCodecKey(it) == selectedCodec
            } ?: return false
        val audio = videoAudio
        if (audio == null) {
            Toast.makeText(context, context.getString(R.string.download_no_compatible_audio), Toast.LENGTH_LONG).show()
            return false
        }
        val subtitle = subtitleChoice?.toDownloadSubtitle() ?: DownloadSubtitle.OFF.takeIf { subtitleOptions.isNotEmpty() }
        DownloadLauncher.startVideoDownload(context, taggedVideo, stream, audio, threads, subtitle)
        if (subtitleOptions.isNotEmpty()) rememberDownloadSubtitleChoice(prefs, subtitleChoice)
        downloadPrefsScope.launch {
            prefs.setLastDownloadVideoChoice(selectedHeight, selectedCodec)
            prefs.setDownloadThreads(threads)
        }
        return true
    }

    val haptics = LocalHapticFeedback.current
    DownloadSheetFrame(
        title = stringResource(R.string.download_video),
        slot = slot,
        onDismiss = onDismiss,
        footer = { sheet ->
            DownloadSheetButton(
                label = stringResource(R.string.download),
                enabled = (isAudioMode && hasAudio) || (!isAudioMode && hasVideo && selectedCodec.isNotEmpty()),
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    if (confirmDownload()) sheet.close()
                },
            )
        },
    ) {
        Column(modifier = Modifier.padding(horizontal = DownloadSheetPadding)) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.download_title_label)) },
                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))

            if (hasVideo && hasAudio) {
                FlowConnectedToggleGroup(
                    options =
                        listOf(
                            FlowToggleOption(
                                value = false,
                                label = stringResource(R.string.video),
                                icon = Icons.Outlined.VideoLibrary,
                            ),
                            FlowToggleOption(
                                value = true,
                                label = stringResource(R.string.download_audio),
                                icon = Icons.Outlined.MusicNote,
                            ),
                        ),
                    selected = isAudioMode,
                    onSelected = { isAudioMode = it },
                    colors = downloadToggleColors(slot),
                )
                Spacer(Modifier.height(16.dp))
            }

            if (!isAudioMode && hasVideo) {
                DownloadDropdownRow(
                    label = stringResource(R.string.quality),
                    value = listOfNotNull("${selectedHeight}p", selectedSizeText).joinToString(separatorDot),
                    options =
                        heights.map { h ->
                            "${h}p" to { selectedHeight = h }
                        },
                )
                Spacer(Modifier.height(10.dp))
                DownloadDropdownRow(
                    label = stringResource(R.string.download_format_label),
                    value = codecOptionLabel(selectedCodec, separatorDot),
                    options =
                        codecsForHeight.map { codec ->
                            codecOptionLabel(codec, separatorDot) to { selectedCodec = codec }
                        },
                )
                if (audioTracks.size > 1) {
                    Spacer(Modifier.height(10.dp))
                    DownloadAudioTrackRow(tracks = audioTracks, selected = videoAudio, onSelect = { videoAudio = it })
                }
                if (subtitleOptions.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    DownloadSubtitleRow(
                        options = subtitleOptions,
                        selected = subtitleChoice,
                        onSelect = { subtitleChoice = it },
                    )
                }
            } else if (isAudioMode && hasAudio) {
                DownloadDropdownRow(
                    label = stringResource(R.string.download_audio),
                    value = audioStreams.getOrNull(selectedAudioIndex)?.let { audioOptionLabel(it, audioLabelStrings) } ?: "",
                    options =
                        audioStreams.mapIndexed { index, stream ->
                            audioOptionLabel(stream, audioLabelStrings) to { selectedAudioIndex = index }
                        },
                )
            } else {
                Text(
                    text = stringResource(R.string.no_download_streams),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.download_threads_label),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                val currentThreads = threads.coerceIn(MIN_THREADS, MAX_THREADS)
                FilledTonalIconButton(
                    onClick = {
                        haptics.performHapticFeedback(
                            if (currentThreads > MIN_THREADS) HapticFeedbackType.SegmentTick else HapticFeedbackType.Reject,
                        )
                        threads = (currentThreads - 1).coerceAtLeast(MIN_THREADS)
                    },
                    enabled = currentThreads > MIN_THREADS,
                ) {
                    Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.download_threads_decrease))
                }
                Text(
                    text = currentThreads.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                FilledTonalIconButton(
                    onClick = {
                        haptics.performHapticFeedback(
                            if (currentThreads < MAX_THREADS) HapticFeedbackType.SegmentTick else HapticFeedbackType.Reject,
                        )
                        threads = (currentThreads + 1).coerceAtMost(MAX_THREADS)
                    },
                    enabled = currentThreads < MAX_THREADS,
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.download_threads_increase))
                }
            }
        }
    }
}
