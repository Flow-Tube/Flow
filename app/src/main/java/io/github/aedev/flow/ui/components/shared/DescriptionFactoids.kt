package io.github.aedev.flow.ui.components.shared

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.pages.VideoDescriptionFactoid
import io.github.aedev.flow.innertube.pages.VideoDescriptionPage
import io.github.aedev.flow.utils.DateDisplayMode
import io.github.aedev.flow.utils.DateFormatStyle
import io.github.aedev.flow.utils.exactUploadTimestamp
import io.github.aedev.flow.utils.formatLikeCount
import io.github.aedev.flow.utils.formatUploadDateConfigured
import io.github.aedev.flow.utils.resolveDisplayUploadTimestamp
import io.github.aedev.flow.utils.withoutStreamOrPremiereWords
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal enum class DescriptionDateKind { UPLOADED, PREMIERED, STREAMED }

/** What the description's date card can say: a calendar day, or only an age. */
internal sealed interface DescriptionDate {
    data class OnDay(
        val timestampMs: Long,
    ) : DescriptionDate

    data class Aged(
        val ageText: String,
        val kind: DescriptionDateKind,
    ) : DescriptionDate
}

internal fun readDescriptionDate(
    dateText: String?,
    timestampMs: Long,
    timestampIsExact: Boolean,
    nowMillis: Long = System.currentTimeMillis(),
    hl: String? = YouTube.locale.hl,
): DescriptionDate {
    val resolved = resolveDisplayUploadTimestamp(dateText, timestampMs, nowMillis, hl)
    val exact = exactUploadTimestamp(dateText, resolved, timestampMs, timestampIsExact, nowMillis, hl)
    if (exact != null && exact > 0L) return DescriptionDate.OnDay(exact)
    val text = dateText?.trim().orEmpty()
    val kind =
        when {
            text.startsWith("premiered ", ignoreCase = true) -> DescriptionDateKind.PREMIERED
            text.startsWith("streamed ", ignoreCase = true) -> DescriptionDateKind.STREAMED
            else -> DescriptionDateKind.UPLOADED
        }
    return DescriptionDate.Aged(withoutStreamOrPremiereWords(text), kind)
}

/**
 * The three cards for a description the server sent no figures for, which is every premiere and past
 * stream (#1466). The date takes the shape YouTube's app gives it, the day on top and the year below,
 * because a whole "Premiered 2 days ago" does not fit a third of the row.
 */
@Composable
internal fun fallbackFactoids(
    video: Video,
    descriptionPage: VideoDescriptionPage?,
    showLikes: Boolean = true,
): List<VideoDescriptionFactoid> {
    val locale = Locale.getDefault()
    val dateText = descriptionPage?.publishedDateText ?: video.uploadDate
    val date =
        when (val read = readDescriptionDate(dateText, video.timestamp, video.timestampIsExact)) {
            is DescriptionDate.OnDay -> {
                val day = Instant.ofEpochMilli(read.timestampMs).atZone(ZoneId.systemDefault())
                VideoDescriptionFactoid(
                    value = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMd"), locale).format(day),
                    label = DateTimeFormatter.ofPattern("y", locale).format(day),
                )
            }

            is DescriptionDate.Aged -> {
                VideoDescriptionFactoid(
                    value =
                        formatUploadDateConfigured(
                            date = read.ageText,
                            mode = DateDisplayMode.RELATIVE,
                            style = DateFormatStyle.SYSTEM,
                            labels = rememberDateLabels(),
                            timestampFallbackMs = video.timestamp,
                            locale = locale,
                            timestampIsExact = video.timestampIsExact,
                        ),
                    label =
                        when (read.kind) {
                            DescriptionDateKind.PREMIERED -> stringResource(R.string.description_premiered)
                            DescriptionDateKind.STREAMED -> stringResource(R.string.description_streamed)
                            DescriptionDateKind.UPLOADED -> stringResource(R.string.uploaded)
                        },
                )
            }
        }
    val likes =
        VideoDescriptionFactoid(
            value = formatLikeCount(video.likeCount.toInt()),
            label = stringResource(R.string.likes),
        )
    return listOfNotNull(
        likes.takeIf { showLikes },
        VideoDescriptionFactoid(
            value = NumberFormat.getIntegerInstance(locale).format(descriptionPage?.viewCount ?: video.viewCount),
            label = stringResource(R.string.views),
        ),
        date,
    )
}
