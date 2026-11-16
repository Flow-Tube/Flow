package io.github.aedev.flow.ui.components.library

import android.text.format.Formatter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.entity.DownloadItemStatus
import io.github.aedev.flow.data.local.entity.DownloadWithItems
import io.github.aedev.flow.data.music.DownloadedTrack
import io.github.aedev.flow.data.video.DownloadProgressUpdate
import io.github.aedev.flow.data.video.DownloadedVideo
import io.github.aedev.flow.ui.components.music.sheet.LocalMusicMenus
import io.github.aedev.flow.ui.components.shared.ArtworkThumbnail
import io.github.aedev.flow.ui.components.shared.ExplicitBadge
import io.github.aedev.flow.ui.components.shared.FlowEmptyState
import io.github.aedev.flow.ui.components.shared.MediaKind
import io.github.aedev.flow.ui.components.shared.MediaRow
import io.github.aedev.flow.ui.components.shared.MediaRowAction
import io.github.aedev.flow.ui.components.shared.MediaRowDefaults
import io.github.aedev.flow.ui.components.shared.MediaThumbnail
import io.github.aedev.flow.ui.components.shared.MediaThumbnailDefaults
import io.github.aedev.flow.ui.components.shared.card.LocalVideoCardPreferences
import io.github.aedev.flow.ui.components.shared.quickactions.VideoQuickActionsBottomSheet
import io.github.aedev.flow.utils.formatYouTubeRelativeTime

private val ProgressBarHeight: Dp = 3.dp
private const val UNKNOWN_QUALITY = "Unknown"
private const val EMPTY_ACTION_WIDTH_FRACTION = 0.55f
private val EmptyActionHeight: Dp = 48.dp

/** "1080p · 482 MB · 2 days ago": what a finished download is, how big, and when it arrived. */
@Composable
internal fun downloadSupportingLine(
    quality: String?,
    sizeBytes: Long,
    downloadedAt: Long,
): String {
    val context = LocalContext.current
    val separator = stringResource(R.string.metadata_separator)
    return listOfNotNull(
        quality?.takeIf { it.isNotBlank() && it != UNKNOWN_QUALITY },
        sizeBytes.takeIf { it > 0L }?.let { Formatter.formatShortFileSize(context, it) },
        downloadedAt.takeIf { it > 0L }?.let { formatYouTubeRelativeTime(it) },
    ).joinToString(" $separator ")
}

/** A finished video download as a row, or as a card in a grid. Long press opens its menu. */
@Composable
internal fun VideoDownloadItem(
    video: DownloadedVideo,
    asCard: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
    rowThumbnailWidth: Dp = MediaThumbnailDefaults.VideoWidth,
) {
    var showMenu by remember { mutableStateOf(false) }
    val supporting = downloadSupportingLine(video.quality, video.fileSize, video.downloadedAt)
    val onLongClick = if (selectionMode) null else ({ showMenu = true })
    if (asCard) {
        LibraryVideoCard(
            title = video.video.title,
            subtitle = video.video.channelName,
            supporting = supporting,
            onClick = onClick,
            onLongClick = onLongClick,
            selected = selected,
            modifier = modifier,
        ) {
            MediaThumbnail(
                videoId = video.video.id,
                thumbnailUrl = video.video.thumbnailUrl,
                durationSeconds = video.video.duration,
                showWatchProgress = LocalVideoCardPreferences.current.showWatchProgress,
                modifier = Modifier.fillMaxWidth(),
                width = Dp.Unspecified,
                shape = MaterialTheme.shapes.large,
            )
        }
    } else {
        MediaRow(
            title = video.video.title,
            modifier = modifier,
            subtitle = video.video.channelName,
            supporting = supporting,
            supportingColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selected = selected,
            onClick = onClick,
            onLongClick = onLongClick,
            leading = if (selectionMode) ({ Checkbox(checked = selected, onCheckedChange = { onClick() }) }) else null,
        ) {
            MediaThumbnail(
                videoId = video.video.id,
                thumbnailUrl = video.video.thumbnailUrl,
                durationSeconds = video.video.duration,
                showWatchProgress = LocalVideoCardPreferences.current.showWatchProgress,
                width = rowThumbnailWidth,
            )
        }
    }
    if (showMenu) {
        VideoQuickActionsBottomSheet(
            video = video.video,
            onDismiss = { showMenu = false },
            onRemoveFromCollection = onDeleteClick,
            removeFromCollectionLabel = stringResource(R.string.delete),
            removeFromCollectionIcon = Icons.Outlined.Delete,
        )
    }
}

/** A finished song download. Long press opens the song menu. */
@Composable
internal fun MusicDownloadRow(
    downloadedTrack: DownloadedTrack,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val musicMenus = LocalMusicMenus.current
    MediaRow(
        title = downloadedTrack.track.title,
        modifier = modifier,
        subtitle = downloadedTrack.track.artist,
        supporting = downloadSupportingLine(downloadedTrack.quality, downloadedTrack.fileSize, downloadedTrack.downloadedAt),
        supportingColor = MaterialTheme.colorScheme.onSurfaceVariant,
        titleMaxLines = 1,
        selected = selected,
        onClick = onClick,
        onLongClick = if (selectionMode) null else ({ musicMenus.openSong(downloadedTrack.track) }),
        leading = if (selectionMode) ({ Checkbox(checked = selected, onCheckedChange = { onClick() }) }) else null,
        subtitleLeading =
            if (downloadedTrack.track.isExplicit == true) {
                { ExplicitBadge(modifier = Modifier.padding(end = 4.dp)) }
            } else {
                null
            },
    ) {
        ArtworkThumbnail(thumbnailUrl = downloadedTrack.track.thumbnailUrl, placeholder = Icons.Outlined.MusicNote)
    }
}

/** A download still running, paused or stopped, with what can be done about it. */
@Composable
internal fun ActiveDownloadRow(
    download: DownloadWithItems,
    progress: DownloadProgressUpdate?,
    isMerging: Boolean,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onRetryClick: () -> Unit,
    onCancelClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val fraction = (progress?.progress ?: download.progress).coerceIn(0f, 1f)
    val percent = (fraction * 100).toInt()
    val status = download.overallStatus
    val isPaused = status == DownloadItemStatus.PAUSED
    val stopped = status == DownloadItemStatus.FAILED || status == DownloadItemStatus.CANCELLED
    val separator = stringResource(R.string.metadata_separator)
    val bytes =
        progress
            ?.takeIf { it.totalBytes > 0L }
            ?.let {
                stringResource(
                    R.string.download_bytes_progress,
                    Formatter.formatShortFileSize(context, it.downloadedBytes),
                    Formatter.formatShortFileSize(context, it.totalBytes),
                )
            }
    val statusText =
        when {
            isMerging -> stringResource(R.string.download_merging_audio_video)
            status == DownloadItemStatus.PENDING -> stringResource(R.string.download_status_queued)
            isPaused -> stringResource(R.string.download_status_paused_template, percent, stringResource(R.string.download_status_paused))
            status == DownloadItemStatus.FAILED -> stringResource(R.string.download_status_failed)
            status == DownloadItemStatus.CANCELLED -> stringResource(R.string.download_status_cancelled)
            bytes != null -> "$bytes $separator ${stringResource(R.string.download_progress_percent, percent)}"
            else -> stringResource(R.string.download_progress_percent, percent)
        }

    val actions: @Composable RowScope.() -> Unit = {
        when {
            stopped -> {
                MediaRowAction(
                    icon = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.retry),
                    onClick = onRetryClick,
                )
            }

            !isMerging -> {
                MediaRowAction(
                    icon = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription = stringResource(if (isPaused) R.string.resume else R.string.pause),
                    onClick = if (isPaused) onResumeClick else onPauseClick,
                )
            }
        }
        MediaRowAction(
            icon = Icons.Outlined.Close,
            contentDescription = stringResource(R.string.cd_delete_download, download.download.title),
            onClick = onCancelClick,
        )
    }
    val actionCount = if (isMerging && !stopped) 1 else 2

    BoxWithConstraints(modifier = modifier) {
        val actionsBelow =
            downloadActionsBelowText(
                rowWidth = maxWidth,
                fontScale = LocalDensity.current.fontScale,
                actionCount = actionCount,
                actionWidth = LocalMinimumInteractiveComponentSize.current,
            )
        MediaRow(
            title = download.download.title,
            subtitle = download.download.uploader,
            supporting = statusText,
            supportingColor = if (stopped) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            trailing = actions.takeUnless { actionsBelow },
            actions = actions.takeIf { actionsBelow },
        ) {
            MediaThumbnail(videoId = download.download.videoId, thumbnailUrl = download.download.thumbnailUrl) {
                if (!stopped) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(ProgressBarHeight)
                                .align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }
}

/**
 * Beside a 152 dp thumbnail, two 48 dp buttons leave a phone's title only a few letters wide, so
 * on a narrow row (or with a large font) the buttons move under the text instead.
 */
internal fun downloadActionsBelowText(
    rowWidth: Dp,
    fontScale: Float,
    actionCount: Int,
    actionWidth: Dp,
): Boolean {
    val fixed = MediaRowDefaults.HorizontalPadding * 2 + MediaThumbnailDefaults.VideoWidth + MediaRowDefaults.Spacing
    val trailing = (actionWidth + MediaRowDefaults.Spacing) * actionCount
    return rowWidth - fixed - trailing < MinTitleWidth * fontScale
}

private val MinTitleWidth = 160.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun DownloadsEmptyState(
    kind: MediaKind,
    onHomeClick: () -> Unit,
) {
    val label = stringResource(kind.labelRes)
    Box(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        FlowEmptyState(
            title = stringResource(R.string.empty_offline_title, label),
            subtitle = stringResource(R.string.empty_offline_body, label),
            icon = kind.icon,
            action = {
                FilledTonalButton(
                    onClick = onHomeClick,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.fillMaxWidth(EMPTY_ACTION_WIDTH_FRACTION).height(EmptyActionHeight),
                ) {
                    Text(stringResource(R.string.action_go_to_home))
                }
            },
        )
    }
}
