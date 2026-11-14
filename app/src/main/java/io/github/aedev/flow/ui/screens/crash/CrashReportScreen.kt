@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.aedev.flow.ui.screens.crash

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.shared.FlowActionButton
import io.github.aedev.flow.ui.components.shared.FlowMaxContentWidth
import io.github.aedev.flow.ui.components.shared.flowActionShape
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val PagePadding = PaddingValues(start = 20.dp, end = 20.dp, top = 32.dp, bottom = 24.dp)
private val IconContainerSize = 72.dp
private val IconSize = 34.dp
private val Spacing = 12.dp
private val CardPadding = 16.dp
private val LabelWidth = 88.dp
private val BarPadding = 20.dp
private const val CLIP_LABEL = "Flow crash report"
private const val BROWSER_PROBE = "https://"

// A github.com link can be caught by the GitHub app; crash issues that skipped the form arrive as a bare title (#2055).
private fun Context.openInBrowser(
    url: String,
    fallback: (String) -> Unit,
) {
    val probe = Intent(Intent.ACTION_VIEW, BROWSER_PROBE.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
    val browsers = packageManager.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName }
    val browser =
        packageManager
            .resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo
            ?.packageName
            ?.takeIf { it in browsers }
            ?: browsers.firstOrNull()
            ?: return fallback(url)
    try {
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(browser))
    } catch (_: ActivityNotFoundException) {
        fallback(url)
    }
}

/**
 * Shown instead of the app after a crash: what happened in plain terms first, the full report
 * below it, and a way to report it on GitHub. Continue clears the report and opens the app.
 */
@Composable
fun CrashReportScreen(
    report: String,
    onContinue: () -> Unit,
) {
    val summary = remember(report) { parseCrashSummary(report) }
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val copiedMessage = stringResource(R.string.crash_report_copied)
    val shareTitle = stringResource(R.string.crash_share)

    fun copy() = scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(CLIP_LABEL, report))) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Row(
                    modifier =
                        Modifier
                            .widthIn(max = FlowMaxContentWidth)
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(BarPadding),
                    horizontalArrangement = Arrangement.spacedBy(Spacing, Alignment.CenterHorizontally),
                ) {
                    FilledTonalButton(
                        onClick = {
                            copy()
                            scope.launch { snackbar.showSnackbar(copiedMessage) }
                            context.openInBrowser(summary.issueUrl(), uriHandler::openUri)
                        },
                        shapes = ButtonDefaults.shapesFor(ButtonDefaults.MediumContainerHeight),
                        contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight, hasStartIcon = true),
                        modifier = Modifier.weight(1f).heightIn(min = ButtonDefaults.MediumContainerHeight),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.padding(end = ButtonDefaults.IconSpacing),
                        )
                        Text(stringResource(R.string.crash_report), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    }
                    FlowActionButton(
                        text = stringResource(R.string.crash_continue),
                        onClick = onContinue,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = FlowMaxContentWidth).fillMaxWidth(),
                contentPadding = PagePadding,
                verticalArrangement = Arrangement.spacedBy(Spacing),
            ) {
                item(key = "header") { CrashHeader() }
                item(key = "summary") { SummaryCard(summary) }
                item(key = "report") {
                    ReportCard(
                        report = report,
                        onCopy = { copy() },
                        onShare = {
                            val send =
                                Intent(Intent.ACTION_SEND)
                                    .setType("text/plain")
                                    .putExtra(Intent.EXTRA_TEXT, report)
                            runCatching { context.startActivity(Intent.createChooser(send, shareTitle)) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CrashHeader() {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing)) {
        Surface(shape = flowActionShape(), color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.size(IconContainerSize)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.BugReport,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(IconSize),
                )
            }
        }
        Text(
            text = stringResource(R.string.crash_title),
            style = MaterialTheme.typography.headlineMediumEmphasized,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(R.string.crash_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SummaryCard(summary: CrashSummary) {
    val timeFormatter = remember { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT) }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(CardPadding), verticalArrangement = Arrangement.spacedBy(Spacing / 2)) {
            SummaryRow(stringResource(R.string.crash_summary_error), summary.exception, monospace = true)
            summary.message?.let { SummaryRow(stringResource(R.string.crash_summary_message), it) }
            summary.location?.let { SummaryRow(stringResource(R.string.crash_summary_where), it, monospace = true) }
            summary.appVersion?.let { SummaryRow(stringResource(R.string.crash_summary_version), it) }
            summary.time?.let { SummaryRow(stringResource(R.string.crash_summary_when), it.format(timeFormatter)) }
        }
    }
}

@Composable
private fun SummaryRow(
    label: String,
    value: String,
    monospace: Boolean = false,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = LabelWidth),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospace) FontFamily.Monospace else null,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ReportCard(
    report: String,
    onCopy: () -> Unit,
    onShare: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = CardPadding, bottom = CardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.crash_full_report),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onCopy) { Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.crash_copy)) }
                IconButton(onClick = onShare) { Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.crash_share)) }
            }
            SelectionContainer(Modifier.horizontalScroll(rememberScrollState()).padding(end = CardPadding)) {
                Text(
                    text = report,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    softWrap = false,
                )
            }
        }
    }
}
