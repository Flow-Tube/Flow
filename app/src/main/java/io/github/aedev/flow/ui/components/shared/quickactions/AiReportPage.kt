package io.github.aedev.flow.ui.components.shared.quickactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.contentfilter.aislist.AiReportCheck
import io.github.aedev.flow.data.contentfilter.aislist.AiVerdict
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.ui.components.shared.FlowLoadingIndicator

private val PagePadding = 16.dp
private val PageSpacing = 12.dp
private val LoadingHeight = 96.dp

/**
 * Confirms a report to AiSList before anything is sent, naming the channel it would report. The
 * channel's handle may need a request to find, so the page opens on a short loading state.
 */
@Composable
internal fun AiReportPage(
    video: Video,
    viewModel: QuickActionsViewModel,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    var check by remember(video.id) { mutableStateOf<AiReportCheck?>(null) }
    LaunchedEffect(video.id) { check = viewModel.checkAiReport(video) }

    QuickActionsPageHeader(title = stringResource(R.string.ai_report_action), onBack = onBack, onClose = onClose)
    Column(
        modifier = Modifier.fillMaxWidth().padding(PagePadding),
        verticalArrangement = Arrangement.spacedBy(PageSpacing),
    ) {
        when (val current = check) {
            null -> {
                Text(stringResource(R.string.ai_report_looking_up), style = MaterialTheme.typography.bodyMedium)
                FlowLoadingIndicator(modifier = Modifier.fillMaxWidth().height(LoadingHeight))
            }

            is AiReportCheck.Ready -> {
                Text(stringResource(R.string.ai_report_confirm, current.handle), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.ai_report_confirm_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        viewModel.sendAiReport(current.handle, video)
                        onClose()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.ai_report_send)) }
            }

            is AiReportCheck.AlreadyListed -> {
                val text = if (current.verdict == AiVerdict.BLOCK) R.string.ai_report_already_blocked else R.string.ai_report_already_warned
                Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
            }

            AiReportCheck.NoName -> {
                Text(stringResource(R.string.ai_report_no_name), style = MaterialTheme.typography.bodyLarge)
            }

            AiReportCheck.NoHandle -> {
                Text(stringResource(R.string.ai_report_no_handle), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
