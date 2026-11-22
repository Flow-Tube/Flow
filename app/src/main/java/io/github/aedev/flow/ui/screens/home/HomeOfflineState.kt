package io.github.aedev.flow.ui.screens.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.ui.components.layout.flowBottomContentPadding
import io.github.aedev.flow.ui.components.shared.FlowErrorState
import io.github.aedev.flow.ui.components.shared.MediaRow
import io.github.aedev.flow.ui.components.shared.MediaThumbnail

/**
 * Home's error state. With nothing cached and something downloaded, the downloads follow the
 * error, so a viewer offline still has something to play instead of a dead end.
 */
@Composable
internal fun HomeErrorState(
    error: String,
    onRetry: () -> Unit,
    onDownloadClick: (Video) -> Unit,
    onOpenDownloads: (() -> Unit)?,
    modifier: Modifier = Modifier,
    viewModel: HomeOfflineViewModel = hiltViewModel(),
) {
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    if (downloads.isEmpty()) {
        FlowErrorState(error = error, onRetry = onRetry, modifier = modifier)
        return
    }
    LazyColumn(modifier = modifier.fillMaxSize()) {
        item(key = "error") {
            FlowErrorState(error = error, onRetry = onRetry, modifier = Modifier.fillMaxWidth())
        }
        item(key = "downloads_header") {
            DownloadsHeader(onOpenDownloads)
        }
        items(downloads, key = { "download:${it.video.id}" }) { download ->
            MediaRow(
                title = download.video.title,
                subtitle = download.video.channelName.ifBlank { null },
                titleMaxLines = 2,
                onClick = { onDownloadClick(download.video) },
                modifier = Modifier.padding(horizontal = RowHorizontalPadding),
                thumbnail = {
                    MediaThumbnail(
                        videoId = download.video.id,
                        thumbnailUrl = download.video.thumbnailUrl,
                        durationSeconds = download.video.duration.takeIf { it > 0 },
                    )
                },
            )
        }
        item(key = "bottom") { Spacer(Modifier.height(flowBottomContentPadding())) }
    }
}

@Composable
private fun DownloadsHeader(onOpenDownloads: (() -> Unit)?) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(if (onOpenDownloads != null) Modifier.clickable(onClick = onOpenDownloads) else Modifier)
                .padding(horizontal = HeaderHorizontalPadding, vertical = HeaderVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = stringResource(R.string.downloads_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.home_offline_downloads_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onOpenDownloads != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val RowHorizontalPadding = 4.dp
private val HeaderHorizontalPadding = 16.dp
private val HeaderVerticalPadding = 8.dp
