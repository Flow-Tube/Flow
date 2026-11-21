package io.github.aedev.flow.ui.components.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.utils.CardTextFormatter
import io.github.aedev.flow.utils.DateContext
import io.github.aedev.flow.utils.DateDisplayMode
import io.github.aedev.flow.utils.formatPremiereDate
import io.github.aedev.flow.utils.formatScheduledStart
import io.github.aedev.flow.utils.upcomingReleaseMs

@Composable
fun videoMetadataLine(
    video: Video,
    isUpcoming: Boolean,
    channelName: String = video.channelName,
    includeChannel: Boolean = false,
): String {
    val dateSettings = rememberDateDisplaySettings()
    if (isUpcoming) {
        val mode = dateSettings.resolve(DateContext.LISTS)
        val releaseMs = remember(video.timestamp, video.uploadDate) { upcomingReleaseMs(video.timestamp, video.uploadDate) }
        val scheduled =
            remember(releaseMs, video.uploadDate, mode) {
                releaseMs?.let { formatScheduledStart(it, mode) } ?: formatPremiereDate(video.uploadDate)
            }
        // "Scheduled for in 2 days" does not read, so a relative span for a stream says "Live in".
        val prefix =
            when {
                !video.isScheduledLive -> R.string.premiere_date_prefix
                releaseMs == null || mode == DateDisplayMode.EXACT -> R.string.scheduled_for_prefix
                else -> R.string.live_in_prefix
            }
        return scheduled
            ?.let { stringResource(prefix, it) }
            ?: video.uploadDate.takeIf(String::isNotBlank)
            ?: stringResource(R.string.premiere_soon)
    }

    val labels = rememberDateLabels()
    val viewsTemplate = stringResource(R.string.views_template)
    val byline =
        remember(video.uploadDate, video.timestamp, video.timestampIsExact, video.viewCount, dateSettings, labels, viewsTemplate) {
            CardTextFormatter.byline(
                uploadDate = video.uploadDate,
                timestamp = video.timestamp,
                timestampIsExact = video.timestampIsExact,
                viewCount = video.viewCount,
                settings = dateSettings,
                labels = labels,
                viewsTemplate = viewsTemplate,
            )
        }
    val uploadedAt = byline.date

    // A zero count means "not reported" — members-only uploads carry no view count at all — so the
    // row shows the date alone rather than a literal "0 views".
    if (video.viewCount <= 0L) {
        return when {
            !includeChannel -> uploadedAt
            uploadedAt.isBlank() -> channelName
            else -> stringResource(R.string.video_metadata_short_template, channelName, uploadedAt)
        }
    }

    // A live stream has viewers but no date, and a row must not end in a dangling separator.
    val views = byline.views
    return when {
        uploadedAt.isBlank() && includeChannel -> stringResource(R.string.video_metadata_short_template, channelName, views)
        uploadedAt.isBlank() -> views
        includeChannel -> stringResource(R.string.video_metadata_template, channelName, views, uploadedAt)
        else -> stringResource(R.string.video_metadata_short_template, views, uploadedAt)
    }
}
