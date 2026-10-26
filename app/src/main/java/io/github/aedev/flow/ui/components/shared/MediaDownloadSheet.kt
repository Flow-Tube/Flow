package io.github.aedev.flow.ui.components.shared

import android.content.Context
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.VideoCodec
import io.github.aedev.flow.data.local.VideoQuality
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.video.DownloadStreamPolicy
import io.github.aedev.flow.data.video.downloader.request.DownloadSubtitle
import io.github.aedev.flow.innertube.models.response.PlayerResponse.StreamingData.Format
import io.github.aedev.flow.player.state.SubtitleOption
import io.github.aedev.flow.player.stream.VideoCodecUtils
import io.github.aedev.flow.ui.components.layout.navigation.LocalMediaNavigator
import io.github.aedev.flow.ui.components.settings.SettingsDestination
import io.github.aedev.flow.ui.components.settings.SettingsTarget
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionsDefaults
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionsHeader

private val SectionGap = 12.dp
private const val VIDEO_COLUMNS = 3
private const val AUDIO_COLUMNS = 2
private val VideoLocationSetting = SettingsTarget(SettingsDestination.DOWNLOADS, highlight = "downloads.video_location")

/**
 * The Classic download menu: every resolution as a tile, the codecs the chosen one comes in, the
 * audio-only tracks and the subtitles on one sheet, with one Download button. It opens on the
 * Download quality from settings. A video with no stream to offer gets the SABR fallback instead.
 * With a [slot] it opens there, under the video, instead of over the screen.
 */
@Composable
fun MediaDownloadSheet(
    streamSizes: Map<String, Long>,
    innerTubeVideoFormats: List<Format>,
    innerTubeAudioFormats: List<Format>,
    video: Video,
    subtitles: List<SubtitleOption> = emptyList(),
    slot: MediaSheetSlot? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember(context) { PlayerPreferences(context) }
    val preferredLang by prefs.preferredAudioLanguage.collectAsState(initial = "")
    val defaultQuality by prefs.defaultDownloadQuality.collectAsState(initial = null)
    val defaultCodec by prefs.defaultDownloadCodec.collectAsState(initial = VideoCodec.AUTO)
    val lastSubtitleLanguage by prefs.lastDownloadSubtitleLanguage.collectAsState(initial = null)

    val qualities =
        remember(innerTubeVideoFormats) {
            DownloadSheetChoices.qualities(DownloadStreamPolicy.buildDownloadVideoFormats(innerTubeVideoFormats))
        }
    val audioStreams = remember(innerTubeAudioFormats) { DownloadStreamPolicy.buildDownloadAudioFormats(innerTubeAudioFormats) }
    val audioTracks = remember(audioStreams) { DownloadStreamPolicy.audioTrackChoices(audioStreams) }
    val subtitleOptions = remember(subtitles) { downloadableSubtitles(subtitles) }
    val preferredCodec = defaultCodec.takeIf { it != VideoCodec.AUTO }?.codecKey

    var chosenHeight by remember(qualities) { mutableStateOf<Int?>(null) }
    var chosenOption by remember(qualities) { mutableStateOf<DownloadFormatOption?>(null) }
    var chosenAudio by remember(audioStreams) { mutableStateOf<Format?>(null) }
    var videoAudio by remember(audioStreams, preferredLang) {
        mutableStateOf(DownloadStreamPolicy.pickAacAudio(audioStreams, preferredLang))
    }
    var allLanguages by remember(audioStreams) { mutableStateOf(false) }
    val primaryAudio =
        remember(audioStreams, preferredLang) {
            DownloadSheetChoices.primaryAudio(audioStreams, DownloadStreamPolicy.pickAacAudio(audioStreams, preferredLang)?.audioTrack?.id)
        }
    var subtitleChoice by remember(subtitleOptions, lastSubtitleLanguage) {
        mutableStateOf(defaultDownloadSubtitle(subtitleOptions, lastSubtitleLanguage))
    }

    val quality =
        qualities.firstOrNull { it.height == chosenHeight }
            ?: defaultQuality?.let { DownloadSheetChoices.preselect(qualities, it.height) }
    val option = quality?.let { DownloadSheetChoices.option(it, chosenOption, preferredCodec) }
    val audioOnly = chosenAudio ?: audioStreams.firstOrNull().takeIf { qualities.isEmpty() }
    val format = if (quality != null && option != null) quality.format(option) else null
    val hdrLabel = stringResource(R.string.download_quality_hdr)
    val separator = stringResource(R.string.list_separator_dot)
    val choiceLabel =
        when {
            audioOnly != null -> {
                audioTileTitle(audioOnly)
            }

            quality == null || option == null -> {
                ""
            }

            else -> {
                listOfNotNull(
                    VideoCodecUtils.qualityLabelWithFrameRate(quality.height, quality.fps),
                    approxDownloadSizeLabel(streamSizes[VideoCodecUtils.streamSizeKey(quality.height, option.codecKey)]),
                ).joinToString(separator)
            }
        }
    val hasStreams = qualities.isNotEmpty() || audioStreams.isNotEmpty()
    val navigator = LocalMediaNavigator.current
    val toggleColors = downloadToggleColors(slot)

    DownloadSheetFrame(
        title = stringResource(R.string.download),
        slot = slot,
        onDismiss = onDismiss,
        footer = { sheet ->
            if (!hasStreams) return@DownloadSheetFrame
            DownloadSheetButton(
                label = stringResource(R.string.download_sheet_button, choiceLabel),
                enabled = audioOnly != null || format != null,
                onClick = {
                    val started =
                        if (audioOnly != null) {
                            DownloadLauncher.startAudioOnlyDownload(context, video, audioOnly).also { queued ->
                                if (queued) {
                                    Toast
                                        .makeText(
                                            context,
                                            context.getString(R.string.ui_started_download, video.title),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                }
                            }
                        } else {
                            startVideo(context, video, format, videoAudio, subtitleOptions, subtitleChoice, prefs)
                        }
                    if (started) sheet.close()
                },
            )
        },
    ) { sheet ->
        DownloadSheetHeader(
            video = video,
            showVideo = slot == null,
            onChangeFolder = {
                sheet.hideThen {
                    onDismiss()
                    navigator.openSettings(VideoLocationSetting)
                }
            },
        )

        if (!hasStreams) {
            NoDownloadStreams(onTrySabr = {
                DownloadLauncher.startDefaultDownload(context, video)
                sheet.close()
            })
            return@DownloadSheetFrame
        }

        if (qualities.isNotEmpty()) {
            FlowSectionHeader(text = stringResource(R.string.video))
            DownloadTileGrid(items = qualities, columns = VIDEO_COLUMNS) { item, modifier ->
                val tileOption = DownloadSheetChoices.option(item, option, preferredCodec)
                DownloadChoiceTile(
                    title = VideoCodecUtils.qualityLabelWithFrameRate(item.height, item.fps),
                    detail =
                        tileOption?.let {
                            approxDownloadSizeLabel(
                                streamSizes[VideoCodecUtils.streamSizeKey(item.height, it.codecKey)],
                            )
                        },
                    badge = resolutionBadge(item.height)?.let { stringResource(it) },
                    checked = audioOnly == null && item == quality,
                    onClick = {
                        chosenHeight = item.height
                        chosenAudio = null
                    },
                    modifier = modifier,
                    colors = toggleColors,
                )
            }
            if (audioOnly == null && quality != null && quality.options.size > 1 && option != null) {
                FlowConnectedToggleGroup(
                    options = quality.options.map { FlowToggleOption(it, formatOptionLabel(it, hdrLabel)) },
                    selected = option,
                    onSelected = { chosenOption = it },
                    colors = toggleColors,
                    modifier = Modifier.padding(start = DownloadSheetPadding, end = DownloadSheetPadding, top = SectionGap),
                )
            }
            if (audioOnly == null && (audioTracks.size > 1 || subtitleOptions.isNotEmpty())) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(start = DownloadSheetPadding, end = DownloadSheetPadding, top = SectionGap),
                ) {
                    if (audioTracks.size > 1) {
                        DownloadAudioTrackRow(tracks = audioTracks, selected = videoAudio, onSelect = { videoAudio = it })
                    }
                    if (subtitleOptions.isNotEmpty()) {
                        DownloadSubtitleRow(options = subtitleOptions, selected = subtitleChoice, onSelect = { subtitleChoice = it })
                    }
                }
            }
        }

        if (audioStreams.isNotEmpty()) {
            FlowSectionHeader(text = stringResource(R.string.ui_audio_only))
            val shownAudio = if (allLanguages) audioStreams else (primaryAudio + listOfNotNull(chosenAudio)).distinct()
            DownloadTileGrid(items = shownAudio, columns = AUDIO_COLUMNS) { stream, modifier ->
                DownloadChoiceTile(
                    title = audioTileTitle(stream),
                    detail = audioTileDetail(stream),
                    badge = null,
                    checked = stream == audioOnly,
                    onClick = { chosenAudio = stream },
                    modifier = modifier,
                    colors = toggleColors,
                )
            }
            if (shownAudio.size < audioStreams.size) {
                TextButton(onClick = { allLanguages = true }, modifier = Modifier.padding(start = 4.dp)) {
                    Text(stringResource(R.string.download_sheet_all_languages))
                }
            }
        }
    }
}

/** Starts the chosen video with [audio], the track picked for it; false when there is none. */
internal fun startVideo(
    context: Context,
    video: Video,
    format: Format?,
    audio: Format?,
    subtitleOptions: List<SubtitleOption>,
    subtitleChoice: SubtitleOption?,
    prefs: PlayerPreferences,
): Boolean {
    format ?: return false
    if (audio == null) {
        Toast.makeText(context, context.getString(R.string.download_no_compatible_audio), Toast.LENGTH_LONG).show()
        return false
    }
    val subtitle = subtitleChoice?.toDownloadSubtitle() ?: DownloadSubtitle.OFF.takeIf { subtitleOptions.isNotEmpty() }
    DownloadLauncher.startVideoDownload(context, video, format, audio, subtitle = subtitle)
    if (subtitleOptions.isNotEmpty()) rememberDownloadSubtitleChoice(prefs, subtitleChoice)
    return true
}

/** The language the video downloads with: the original or one of its dubs. */
@Composable
internal fun DownloadAudioTrackRow(
    tracks: List<Format>,
    selected: Format?,
    onSelect: (Format) -> Unit,
) {
    DownloadDropdownRow(
        label = stringResource(R.string.audio_track),
        value = selected?.let { audioTrackLabel(it) }.orEmpty(),
        options = tracks.map { track -> audioTrackLabel(track) to { onSelect(track) } },
    )
}

/** YouTube already names the original track "… original"; a track named only by its locale gets the word added. */
@Composable
private fun audioTrackLabel(track: Format): String {
    val original = stringResource(R.string.audio_track_original)
    val language = DownloadStreamPolicy.audioLanguageLabel(track) ?: return original
    return if (track.isOriginal && !language.contains(original, ignoreCase = true)) {
        listOf(language, original).joinToString(stringResource(R.string.list_separator_dot))
    } else {
        language
    }
}

internal fun resolutionBadge(height: Int): Int? =
    when {
        height >= VideoQuality.Q_2160P.height -> R.string.filter_4k
        height >= VideoQuality.Q_1440P.height -> R.string.quality_badge_2k
        height >= VideoQuality.Q_1080P.height -> R.string.filter_hd
        else -> null
    }

private fun formatOptionLabel(
    option: DownloadFormatOption,
    hdrLabel: String,
): String {
    val codec = VideoCodecUtils.codecLabelFromKey(option.codecKey)
    return if (option.hdr) "$codec $hdrLabel" else codec
}

@Composable
private fun audioTileTitle(stream: Format): String =
    "${DownloadStreamPolicy.audioFormatLabel(stream, stringResource(R.string.audio_format_unknown))} " +
        "${DownloadStreamPolicy.audioBitrateKbps(stream)} ${stringResource(R.string.kbps)}"

@Composable
private fun audioTileDetail(stream: Format): String? {
    val parts =
        listOfNotNull(
            approxDownloadSizeLabel(stream.contentLength),
            DownloadStreamPolicy.audioLanguageLabel(stream),
            stringResource(R.string.audio_track_dubbed).takeUnless { stream.isOriginal },
        )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(stringResource(R.string.list_separator_dot))
}

/** The video, and the folder its file goes to with the space left there, as one card. */
@Composable
private fun DownloadSheetHeader(
    video: Video,
    showVideo: Boolean,
    onChangeFolder: () -> Unit,
) {
    val context = LocalContext.current
    val folder by produceState<DownloadSaveFolder?>(null) { value = DownloadLauncher.videoSaveFolder(context) }
    if (!showVideo && folder == null) return
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = DownloadSheetPadding),
    ) {
        Column(modifier = Modifier.padding(top = SectionGap, bottom = 4.dp)) {
            if (showVideo) {
                QuickActionsHeader(title = video.title, subtitle = video.channelName) {
                    MediaThumbnail(
                        videoId = video.id,
                        thumbnailUrl = video.thumbnailUrl,
                        width = QuickActionsDefaults.VideoArtworkWidth,
                        shape = MaterialTheme.shapes.medium,
                        durationSeconds = video.duration.takeIf { it > 0 },
                    )
                }
            }
            folder?.let { saveFolder ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = DownloadSheetPadding, end = 4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text =
                            stringResource(
                                R.string.download_sheet_save_folder,
                                saveFolder.path,
                                Formatter.formatShortFileSize(context, saveFolder.freeBytes),
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onChangeFolder) { Text(stringResource(R.string.download_sheet_change_folder)) }
                }
            }
        }
    }
}

@Composable
private fun NoDownloadStreams(onTrySabr: () -> Unit) {
    Text(
        text = stringResource(R.string.no_download_streams),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(DownloadSheetPadding),
    )
    Button(
        onClick = onTrySabr,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = DownloadSheetPadding),
    ) {
        Text(stringResource(R.string.ui_try_sabr_download))
    }
}
