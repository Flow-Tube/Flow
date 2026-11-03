package io.github.aedev.flow.ui.screens.settings.downloads

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.MediaCacheSizes
import io.github.aedev.flow.data.repository.MediaCacheType
import io.github.aedev.flow.data.repository.MediaCacheUsage
import io.github.aedev.flow.ui.components.settings.SettingsListScope
import io.github.aedev.flow.ui.components.shared.FlowChoice
import io.github.aedev.flow.ui.components.shared.FlowChoiceDialog
import io.github.aedev.flow.ui.components.shared.FlowNavRow
import io.github.aedev.flow.ui.screens.settings.index.DownloadsIndex

private val UsageSpacing = 8.dp
private val BarHeight = 8.dp
private val BarGap = 2.dp
private val LegendDot = 8.dp
private val LegendSpacing = 6.dp
private val LegendRowSpacing = 12.dp

/** Each cache in the meter, in the order the bar and the rows show them. */
private val MeteredTypes = listOf(MediaCacheType.VIDEOS, MediaCacheType.SONGS, MediaCacheType.ARTWORK, MediaCacheType.OTHER)

/**
 * The Cache group of Downloads: one meter split by type, then a row per cache with its size, its
 * limit and Clear. The caches are measured once when the page opens.
 */
internal fun SettingsListScope.downloadCacheSection(
    viewModel: DownloadCacheViewModel,
    onEditLimit: (MediaCacheType) -> Unit,
) {
    group(key = "downloads.cache", header = R.string.cache_group_header, footer = R.string.cache_size_desc) {
        row(DownloadsIndex.cacheUsage.key) { shape ->
            val usage by viewModel.usage.collectAsStateWithLifecycle()
            CacheUsageRow(usage, shape)
        }
        row(DownloadsIndex.videoCache.key) { shape ->
            val limit by viewModel.videoLimitMb.collectAsStateWithLifecycle()
            CacheRow(MediaCacheType.VIDEOS, limit, viewModel, shape, onClick = { onEditLimit(MediaCacheType.VIDEOS) })
        }
        row(DownloadsIndex.songCache.key) { shape ->
            val limit by viewModel.songLimitMb.collectAsStateWithLifecycle()
            CacheRow(MediaCacheType.SONGS, limit, viewModel, shape, onClick = { onEditLimit(MediaCacheType.SONGS) })
        }
        row(DownloadsIndex.artworkCache.key) { shape ->
            val limit by viewModel.artworkLimitMb.collectAsStateWithLifecycle()
            CacheRow(MediaCacheType.ARTWORK, limit, viewModel, shape, onClick = { onEditLimit(MediaCacheType.ARTWORK) })
        }
        row(DownloadsIndex.otherCache.key) { shape ->
            CacheRow(MediaCacheType.OTHER, null, viewModel, shape, onClick = null)
        }
    }
}

/** The size choice for one cache; picking a size saves it for the next start. */
@Composable
internal fun DownloadCacheLimitDialog(
    type: MediaCacheType,
    viewModel: DownloadCacheViewModel,
    onDismiss: () -> Unit,
) {
    val state =
        when (type) {
            MediaCacheType.VIDEOS -> viewModel.videoLimitMb
            MediaCacheType.SONGS -> viewModel.songLimitMb
            MediaCacheType.ARTWORK -> viewModel.artworkLimitMb
            MediaCacheType.OTHER -> return
        }
    val selected by state.collectAsStateWithLifecycle()
    var typingCustom by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(type.sizeTitle())
    val artwork = type == MediaCacheType.ARTWORK
    val presets = if (artwork) MediaCacheSizes.ARTWORK_OPTIONS_MB else MediaCacheSizes.MEDIA_OPTIONS_MB
    val custom = selected.takeIf { it !in presets }
    if (typingCustom) {
        DownloadCacheCustomSizeDialog(
            title = title,
            currentMb = custom,
            onConfirm = { viewModel.setLimit(type, it) },
            onDismiss = onDismiss,
        )
        return
    }
    val customChoice =
        FlowChoice(
            CUSTOM_CHOICE,
            stringResource(R.string.cache_size_custom),
            custom?.let { cacheSizeText(it) },
        )
    FlowChoiceDialog(
        title = title,
        description = stringResource(R.string.cache_size_applies_on_restart),
        options = presets.map { FlowChoice(it, cacheSizeText(it, artwork)) } + listOfNotNull(customChoice.takeUnless { artwork }),
        selected = if (custom != null && !artwork) CUSTOM_CHOICE else selected,
        onSelect = { if (it == CUSTOM_CHOICE) typingCustom = true else viewModel.setLimit(type, it) },
        onDismiss = { if (!typingCustom) onDismiss() },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CacheUsageRow(
    usage: MediaCacheUsage?,
    shape: Shape,
) {
    val context = LocalContext.current
    SegmentedListItem(
        verticalAlignment = Alignment.CenterVertically,
        shapes = ListItemDefaults.shapes(shape = shape),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        leadingContent = { Icon(Icons.Outlined.Storage, contentDescription = null) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(UsageSpacing)) {
                Text(
                    usage?.let { stringResource(R.string.cache_in_use, Formatter.formatShortFileSize(context, it.total)) }
                        ?: stringResource(R.string.cache_measuring),
                )
                CacheBar(usage)
                if (usage != null) CacheLegend(usage)
            }
        },
    ) {
        Text(stringResource(DownloadsIndex.cacheUsage.title))
    }
}

/** One bar split by each cache's share of the total; an empty or unmeasured cache leaves only the track. */
@Composable
private fun CacheBar(usage: MediaCacheUsage?) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(BarHeight)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        horizontalArrangement = Arrangement.spacedBy(BarGap),
    ) {
        val total = usage?.total ?: 0L
        if (usage != null && total > 0L) {
            MeteredTypes.forEach { type ->
                val bytes = usage.of(type)
                if (bytes > 0L) {
                    Box(
                        Modifier
                            .weight(bytes.toFloat() / total)
                            .height(BarHeight)
                            .background(type.color()),
                    )
                }
            }
        }
    }
}

@Composable
private fun CacheLegend(usage: MediaCacheUsage) {
    val context = LocalContext.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(LegendRowSpacing)) {
        MeteredTypes.forEach { type ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LegendSpacing)) {
                Box(
                    Modifier
                        .size(LegendDot)
                        .clip(CircleShape)
                        .background(type.color()),
                )
                Text(
                    text = "${stringResource(type.title())} ${Formatter.formatShortFileSize(context, usage.of(type))}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CacheRow(
    type: MediaCacheType,
    limitMb: Int?,
    viewModel: DownloadCacheViewModel,
    shape: Shape,
    onClick: (() -> Unit)?,
) {
    val context = LocalContext.current
    val usage by viewModel.usage.collectAsStateWithLifecycle()
    val used = usage?.let { Formatter.formatShortFileSize(context, it.of(type)) }
    val summary =
        when {
            used == null -> {
                stringResource(R.string.cache_measuring)
            }

            type == MediaCacheType.OTHER -> {
                stringResource(R.string.cache_other_summary, used)
            }

            type == MediaCacheType.ARTWORK && limitMb == MediaCacheSizes.ARTWORK_AUTOMATIC_MB -> {
                stringResource(
                    R.string.cache_used_automatic,
                    used,
                )
            }

            limitMb == null || limitMb == MediaCacheSizes.UNLIMITED_MB -> {
                stringResource(R.string.cache_used_no_limit, used)
            }

            else -> {
                stringResource(R.string.cache_used_of_limit, used, cacheSizeText(limitMb, plain = true))
            }
        }
    val clear: @Composable () -> Unit = {
        TextButton(onClick = { viewModel.clear(type) }) { Text(stringResource(R.string.clear)) }
    }
    if (onClick != null) {
        FlowNavRow(
            title = stringResource(type.title()),
            supportingText = summary,
            onClick = onClick,
            showChevron = false,
            leadingIcon = type.icon(),
            shape = shape,
            trailingContent = clear,
        )
    } else {
        SegmentedListItem(
            verticalAlignment = Alignment.CenterVertically,
            shapes = ListItemDefaults.shapes(shape = shape),
            colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            leadingContent = { Icon(type.icon(), contentDescription = null) },
            supportingContent = { Text(summary) },
            trailingContent = clear,
        ) {
            Text(stringResource(type.title()))
        }
    }
}

private fun MediaCacheType.title(): Int =
    when (this) {
        MediaCacheType.VIDEOS -> R.string.cache_videos
        MediaCacheType.SONGS -> R.string.cache_songs
        MediaCacheType.ARTWORK -> R.string.cache_artwork
        MediaCacheType.OTHER -> R.string.cache_other
    }

private fun MediaCacheType.sizeTitle(): Int =
    when (this) {
        MediaCacheType.VIDEOS -> R.string.cache_videos_size_title
        MediaCacheType.SONGS -> R.string.cache_songs_size_title
        MediaCacheType.ARTWORK, MediaCacheType.OTHER -> R.string.cache_artwork_size_title
    }

private fun MediaCacheType.icon(): ImageVector =
    when (this) {
        MediaCacheType.VIDEOS -> Icons.Outlined.Movie
        MediaCacheType.SONGS -> Icons.Outlined.MusicNote
        MediaCacheType.ARTWORK -> Icons.Outlined.Image
        MediaCacheType.OTHER -> Icons.Outlined.Description
    }

@Composable
private fun MediaCacheType.color(): Color =
    when (this) {
        MediaCacheType.VIDEOS -> MaterialTheme.colorScheme.primary
        MediaCacheType.SONGS -> MaterialTheme.colorScheme.tertiary
        MediaCacheType.ARTWORK -> MaterialTheme.colorScheme.secondary
        MediaCacheType.OTHER -> MaterialTheme.colorScheme.outline
    }

/**
 * [artwork] reads 0 as automatic rather than unlimited; [plain] drops the "(Default)" mark of 500 MB.
 * A size typed in by hand reads in MB below 1 GB and in GB from there.
 */
@Composable
private fun cacheSizeText(
    megabytes: Int,
    artwork: Boolean = false,
    plain: Boolean = false,
): String =
    when (megabytes) {
        0 -> {
            stringResource(if (artwork) R.string.cache_size_automatic else R.string.cache_size_unlimited)
        }

        100 -> {
            stringResource(R.string.cache_size_100mb)
        }

        200 -> {
            stringResource(R.string.cache_size_200mb)
        }

        500 -> {
            stringResource(if (plain || artwork) R.string.cache_size_500mb_plain else R.string.cache_size_500mb)
        }

        1024 -> {
            stringResource(R.string.cache_size_1gb)
        }

        2048 -> {
            stringResource(R.string.cache_size_2gb)
        }

        5120 -> {
            stringResource(R.string.cache_size_5gb)
        }

        else -> {
            val unit = CacheSizeInput.unitFor(megabytes)
            val number = CacheSizeInput.format(megabytes, unit)
            stringResource(if (unit == CacheSizeUnit.GB) R.string.cache_size_gb_value else R.string.cache_size_mb_value, number)
        }
    }

private const val CUSTOM_CHOICE = -1
