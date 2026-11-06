package io.github.aedev.flow.ui.screens.settings.wellbeing

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.shared.FlowLoadingIndicator
import io.github.aedev.flow.ui.components.stats.spentTimeLabel
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private val CardPadding = 20.dp
private val ChartHeight = 132.dp
private val ChartTopSpacing = 20.dp
private val BarSpacing = 8.dp
private val LabelSpacing = 6.dp
private const val MIN_BAR_FRACTION = 0.02f
private const val BAR_WIDTH_FRACTION = 0.6f

/**
 * The daily average over the last week, above one bar per day. Today's bar is in the primary colour
 * so it can be told apart from the rest. Tapping a bar shows that day's time instead of the average,
 * and tapping it again goes back.
 */
@Composable
internal fun WatchTimeCard(
    summary: WatchTimeSummary?,
    modifier: Modifier = Modifier,
) {
    var selectedEpochDay by rememberSaveable { mutableStateOf<Long?>(null) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.padding(CardPadding)) {
            val selectedDay = summary?.dayOrNull(selectedEpochDay?.let(LocalDate::ofEpochDay))
            Text(
                text =
                    if (summary != null && selectedDay != null) {
                        dayLabel(selectedDay.date, summary.days.last().date)
                    } else {
                        stringResource(R.string.daily_average_label)
                    },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (summary == null) {
                Box(Modifier.fillMaxWidth().height(ChartHeight), contentAlignment = Alignment.Center) { FlowLoadingIndicator() }
                return@Column
            }
            Text(
                text = spentTimeLabel(selectedDay?.millis ?: summary.dailyAverageMillis),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.settings_watch_time_week_total, spentTimeLabel(summary.weekMillis)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(ChartTopSpacing))
            if (summary.isEmpty) {
                Box(Modifier.fillMaxWidth().height(ChartHeight), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.no_watch_data),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                WeekChart(
                    summary = summary,
                    selected = selectedDay?.date,
                    onDayClick = { date ->
                        selectedEpochDay = date.toEpochDay().takeUnless { date == selectedDay?.date }
                    },
                )
            }
        }
    }
}

@Composable
private fun dayLabel(
    date: LocalDate,
    today: LocalDate,
): String =
    when (date) {
        today -> stringResource(R.string.time_today)
        today.minusDays(1) -> stringResource(R.string.time_yesterday)
        else -> date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
    }

@Composable
private fun WeekChart(
    summary: WatchTimeSummary,
    selected: LocalDate?,
    onDayClick: (LocalDate) -> Unit,
) {
    val peak = summary.days.maxOf { it.millis }.coerceAtLeast(1L)
    val marked = selected ?: summary.days.last().date
    val locale = Locale.getDefault()
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(BarSpacing),
    ) {
        summary.days.forEach { day ->
            val isMarked = day.date == marked
            val barColor by animateColorAsState(
                targetValue = if (isMarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                label = "watchTimeBar",
            )
            val description = "${day.date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)} ${spentTimeLabel(day.millis)}"
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .clip(MaterialTheme.shapes.small)
                        .selectable(
                            selected = day.date == selected,
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                onDayClick(day.date)
                            },
                        ).semantics { contentDescription = description },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(modifier = Modifier.fillMaxWidth().height(ChartHeight), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth(BAR_WIDTH_FRACTION)
                                .fillMaxHeight((day.millis.toFloat() / peak).coerceIn(MIN_BAR_FRACTION, 1f))
                                .clip(MaterialTheme.shapes.small)
                                .background(barColor),
                    )
                }
                Spacer(Modifier.height(LabelSpacing))
                Text(
                    text = day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isMarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }
    }
}
