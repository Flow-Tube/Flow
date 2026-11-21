package io.github.aedev.flow.ui.components.shared.quickactions

import android.content.ClipData
import android.os.Build
import android.text.format.Formatter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.R
import io.github.aedev.flow.data.newmusic.InnertubeMusicService
import io.github.aedev.flow.innertube.models.MediaInfo
import io.github.aedev.flow.ui.components.shared.FlowLoadingIndicator
import io.github.aedev.flow.ui.components.shared.FlowNavRow
import io.github.aedev.flow.ui.components.shared.rememberDateDisplaySettings
import io.github.aedev.flow.ui.components.shared.rememberDateLabels
import io.github.aedev.flow.utils.DateContext
import io.github.aedev.flow.utils.formatDuration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.NumberFormat
import javax.inject.Inject

/** What the Details page knows about an item before anything is fetched. */
data class MediaDetailsSubject(
    val videoId: String,
    val title: String,
    val author: String,
    val album: String? = null,
    val channelId: String? = null,
    val viewCount: Long? = null,
    val likeCount: Long? = null,
    val uploadDate: String? = null,
    val timestamp: Long? = null,
    val timestampIsExact: Boolean = false,
    val durationSeconds: Int? = null,
    val whyShown: String? = null,
)

data class MediaDetailsState(
    val info: MediaInfo? = null,
    val durationSeconds: Int? = null,
    val isLoading: Boolean = true,
)

/** Loads an item's media info once per item and keeps it, so reopening Details asks nothing again. */
@HiltViewModel
class MediaDetailsViewModel
    @Inject
    constructor() : ViewModel() {
        private val cache = HashMap<String, MediaDetailsState>()
        private val _state = MutableStateFlow(MediaDetailsState())
        val state: StateFlow<MediaDetailsState> = _state.asStateFlow()

        fun load(subject: MediaDetailsSubject) {
            cache[subject.videoId]?.let {
                _state.value = it
                return
            }
            _state.value = MediaDetailsState(durationSeconds = subject.durationSeconds?.takeIf { it > 0 })
            viewModelScope.launch {
                val info = runCatching { InnertubeMusicService.getMediaInfo(subject.videoId) }.getOrNull()
                val duration =
                    info?.durationSeconds?.takeIf { it > 0 }
                        ?: subject.durationSeconds?.takeIf { it > 0 }
                val loaded = MediaDetailsState(info = info, durationSeconds = duration, isLoading = false)
                cache[subject.videoId] = loaded
                _state.value = loaded
            }
        }
    }

/** The Details page: each fact as a row that copies its value. */
@Composable
fun MediaDetailsPage(
    subject: MediaDetailsSubject,
    viewModel: MediaDetailsViewModel = hiltViewModel(),
    quickActions: QuickActionsViewModel = sharedQuickActionsViewModel(),
) {
    LaunchedEffect(subject.videoId) { viewModel.load(subject) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    MediaDetailRows(details = mediaDetailLines(subject, state), quickActions = quickActions)
    if (state.isLoading) {
        Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            FlowLoadingIndicator()
        }
    }
}

/** Facts as rows of a details page, each copying its value when tapped. */
@Composable
fun MediaDetailRows(
    details: List<Pair<String, String>>,
    quickActions: QuickActionsViewModel = sharedQuickActionsViewModel(),
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    QuickActionsGroup(
        title = null,
        rows =
            details.map { (label, value) ->
                QuickActionRow(key = label) { shape ->
                    FlowNavRow(
                        title = value,
                        supportingText = label,
                        onClick = {
                            scope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, value)))
                                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                    quickActions.announce(R.string.copied_to_clipboard, label)
                                }
                            }
                        },
                        shape = shape,
                        trailingContent = { Icon(imageVector = Icons.Outlined.ContentCopy, contentDescription = null) },
                    )
                }
            },
    )
}

@Composable
private fun mediaDetailLines(
    subject: MediaDetailsSubject,
    state: MediaDetailsState,
): List<Pair<String, String>> {
    val context = LocalContext.current
    val dateSettings = rememberDateDisplaySettings()
    val dateLabels = rememberDateLabels()
    val numbers = NumberFormat.getIntegerInstance()
    val info = state.info
    val lines = mutableListOf<Pair<String, String?>>()
    lines += stringResource(R.string.title_label) to (info?.title ?: subject.title)
    lines += stringResource(R.string.details_why_shown) to subject.whyShown
    lines += stringResource(R.string.artist_label) to (info?.author ?: subject.author)
    lines += stringResource(R.string.album_label) to subject.album?.takeIf { it.isNotBlank() }
    lines += stringResource(R.string.views) to (info?.viewCount?.toLong() ?: subject.viewCount?.takeIf { it > 0 })?.let(numbers::format)
    lines += stringResource(R.string.likes) to (info?.like?.toLong() ?: subject.likeCount?.takeIf { it > 0 })?.let(numbers::format)
    lines += stringResource(R.string.dislikes) to info?.dislike?.let { numbers.format(it.toLong()) }
    lines += stringResource(R.string.subscribers) to info?.subscribers
    lines += stringResource(R.string.duration) to state.durationSeconds?.let { formatDuration(it) }
    lines += stringResource(R.string.uploaded) to
        (
            info?.uploadDate?.let { dateSettings.format(it, DateContext.WATCH, dateLabels) }
                ?: subject.uploadDate?.takeIf { it.isNotBlank() }?.let {
                    dateSettings.format(
                        it,
                        DateContext.WATCH,
                        dateLabels,
                        subject.timestamp ?: 0L,
                        subject.timestampIsExact,
                    )
                }
        )
    lines += stringResource(R.string.video_id_label) to subject.videoId
    lines += stringResource(R.string.channel_id) to (info?.authorId ?: subject.channelId?.takeIf { it.isNotBlank() })
    lines += stringResource(R.string.quality) to info?.qualityLabel
    lines += stringResource(R.string.itag) to info?.videoId_tag?.toString()
    lines += stringResource(R.string.mime_type) to info?.mimeType
    lines +=
        stringResource(R.string.bitrate_label) to info?.bitrate?.let { "${numbers.format(it / 1000)} ${stringResource(R.string.kbps)}" }
    lines +=
        stringResource(R.string.sample_rate_label) to
        info?.sampleRate?.let { "${numbers.format(it.toLong())} ${stringResource(R.string.hz)}" }
    lines += stringResource(R.string.file_size) to info?.contentLength?.toLongOrNull()?.let { Formatter.formatShortFileSize(context, it) }
    return lines.mapNotNull { (label, value) -> value?.takeIf { it.isNotBlank() }?.let { label to it } }
}
