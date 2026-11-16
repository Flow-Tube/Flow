package io.github.aedev.flow.ui.screens.settings.integrations

import android.text.format.DateUtils
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.MusicOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R
import io.github.aedev.flow.data.contentfilter.aislist.AiListStatus
import io.github.aedev.flow.ui.components.settings.SettingsListScope
import io.github.aedev.flow.ui.components.settings.info
import io.github.aedev.flow.ui.components.settings.nav
import io.github.aedev.flow.ui.components.settings.switch
import io.github.aedev.flow.ui.screens.settings.index.IntegrationsIndex
import java.text.NumberFormat

private const val AISLIST_HOMEPAGE = "https://aisloplist.com"
private const val SOUL_OVER_AI_HOMEPAGE = "https://souloverai.com"
private const val SPOTIFY_AI_BLOCKER_HOMEPAGE = "https://github.com/CennoxX/spotify-ai-blocker"

/**
 * The AiSList channel filter and the AI music filter. The status row shows only while a switch is
 * on, since nothing is downloaded before. The source rows are the attribution each list's licence
 * asks for; the music list's two show while its switch is on, when it is downloaded and used.
 */
internal fun SettingsListScope.aiListSection(
    viewModel: IntegrationsViewModel,
    statusTitle: String,
    statusText: String?,
    musicOn: Boolean,
    sourceLabel: String,
    reportName: String,
    reportEnabled: Boolean,
    onOpenSource: (String) -> Unit,
    onEditServer: () -> Unit,
    onEditReportName: () -> Unit,
) {
    group(key = "integrations.ai_list.group", header = R.string.ai_list_title, footer = R.string.ai_list_footer) {
        switch(IntegrationsIndex.aiListBlock, viewModel.aiListBlock, viewModel::setAiListBlock, iconRes = R.drawable.ic_ai_content)
        switch(IntegrationsIndex.aiListWarn, viewModel.aiListWarn, viewModel::setAiListWarn)
        switch(IntegrationsIndex.aiMusicBlock, viewModel.aiMusic, viewModel::setAiMusic, icon = Icons.Outlined.MusicOff)
        if (statusText != null) info(key = "integrations.ai_list.status", title = statusTitle, value = statusText)
        nav(IntegrationsIndex.aiListServer, onClick = onEditServer, value = sourceLabel, icon = Icons.Outlined.Dns)
        nav(IntegrationsIndex.aiListSource, onClick = { onOpenSource(AISLIST_HOMEPAGE) }, icon = Icons.AutoMirrored.Outlined.OpenInNew)
        if (musicOn) {
            nav(
                IntegrationsIndex.aiMusicSoulSource,
                onClick = { onOpenSource(SOUL_OVER_AI_HOMEPAGE) },
                icon = Icons.AutoMirrored.Outlined.OpenInNew,
            )
            nav(
                IntegrationsIndex.aiMusicBlockerSource,
                onClick = { onOpenSource(SPOTIFY_AI_BLOCKER_HOMEPAGE) },
                icon = Icons.AutoMirrored.Outlined.OpenInNew,
            )
        }
    }
    group(key = "integrations.ai_report.group", header = R.string.ai_report_header, footer = R.string.ai_report_footer) {
        switch(IntegrationsIndex.aiReport, viewModel.aiReport, viewModel::setAiReport, icon = Icons.Outlined.Flag)
        nav(IntegrationsIndex.aiReportName, onClick = onEditReportName, value = reportName, enabled = reportEnabled)
    }
}

/**
 * "60,669 channels and 9,812 artists, updated 3 hours ago" for the lists in use, or the not-yet
 * line; null while the filter is off.
 */
@Composable
internal fun aiListStatusText(
    isOn: Boolean,
    status: AiListStatus?,
): String? {
    if (!isOn) return null
    val channels = status?.channelCount
    val artists = status?.artistCount
    if (status == null || (channels == null && artists == null)) return stringResource(R.string.ai_list_status_pending)
    val format = NumberFormat.getIntegerInstance()
    val now = System.currentTimeMillis() - status.updatedAtMs < DateUtils.MINUTE_IN_MILLIS
    val updated = DateUtils.getRelativeTimeSpanString(status.updatedAtMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
    return when {
        channels != null && artists != null -> {
            if (now) {
                stringResource(R.string.ai_lists_status_count_now, format.format(channels), format.format(artists))
            } else {
                stringResource(R.string.ai_lists_status_count, format.format(channels), format.format(artists), updated)
            }
        }

        channels != null -> {
            if (now) {
                stringResource(R.string.ai_list_status_count_now, format.format(channels))
            } else {
                stringResource(R.string.ai_list_status_count, format.format(channels), updated)
            }
        }

        else -> {
            val count = format.format(artists)
            if (now) {
                stringResource(R.string.ai_music_status_count_now, count)
            } else {
                stringResource(R.string.ai_music_status_count, count, updated)
            }
        }
    }
}
