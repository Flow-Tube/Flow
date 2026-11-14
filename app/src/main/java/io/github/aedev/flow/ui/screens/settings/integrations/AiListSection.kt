package io.github.aedev.flow.ui.screens.settings.integrations

import android.text.format.DateUtils
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.SmartToy
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

/**
 * The AiSList channel filter. The status row shows only while a switch is on, since nothing is
 * downloaded before. The source row is the attribution the list's CC BY-NC licence asks for.
 */
internal fun SettingsListScope.aiListSection(
    viewModel: IntegrationsViewModel,
    statusTitle: String,
    statusText: String?,
    sourceLabel: String,
    reportName: String,
    reportEnabled: Boolean,
    onOpenSource: (String) -> Unit,
    onEditServer: () -> Unit,
    onEditReportName: () -> Unit,
) {
    group(key = "integrations.ai_list.group", header = R.string.ai_list_title, footer = R.string.ai_list_footer) {
        switch(IntegrationsIndex.aiListBlock, viewModel.aiListBlock, viewModel::setAiListBlock, icon = Icons.Outlined.SmartToy)
        switch(IntegrationsIndex.aiListWarn, viewModel.aiListWarn, viewModel::setAiListWarn)
        if (statusText != null) info(key = "integrations.ai_list.status", title = statusTitle, value = statusText)
        nav(IntegrationsIndex.aiListServer, onClick = onEditServer, value = sourceLabel, icon = Icons.Outlined.Dns)
        nav(IntegrationsIndex.aiListSource, onClick = { onOpenSource(AISLIST_HOMEPAGE) }, icon = Icons.AutoMirrored.Outlined.OpenInNew)
    }
    group(key = "integrations.ai_report.group", header = R.string.ai_report_header, footer = R.string.ai_report_footer) {
        switch(IntegrationsIndex.aiReport, viewModel.aiReport, viewModel::setAiReport, icon = Icons.Outlined.Flag)
        nav(IntegrationsIndex.aiReportName, onClick = onEditReportName, value = reportName, enabled = reportEnabled)
    }
}

/** "60,669 channels, updated 3 hours ago", or the not-yet line; null while the filter is off. */
@Composable
internal fun aiListStatusText(
    isOn: Boolean,
    status: AiListStatus?,
): String? =
    when {
        !isOn -> {
            null
        }

        status == null -> {
            stringResource(R.string.ai_list_status_pending)
        }

        System.currentTimeMillis() - status.updatedAtMs < DateUtils.MINUTE_IN_MILLIS -> {
            stringResource(R.string.ai_list_status_count_now, NumberFormat.getIntegerInstance().format(status.channelCount))
        }

        else -> {
            stringResource(
                R.string.ai_list_status_count,
                NumberFormat.getIntegerInstance().format(status.channelCount),
                DateUtils.getRelativeTimeSpanString(status.updatedAtMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
            )
        }
    }
