package io.github.aedev.flow.ui.components.search

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.innertube.pages.search.SearchCorrection
import io.github.aedev.flow.innertube.pages.search.SearchCorrectionRun

/**
 * Says when YouTube changed the query, above the results, with the corrected words in italics as
 * YouTube marks them. "Search instead for" runs the typed text exactly; "Did you mean" runs the
 * suggestion as a new search.
 */
@Composable
fun SearchCorrectionRow(
    correction: SearchCorrection,
    onSearchExactly: (String) -> Unit,
    onSearchCorrected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = RowHorizontalPadding)) {
        when (correction.kind) {
            SearchCorrection.Kind.SHOWING_RESULTS_FOR -> {
                Text(
                    text = templated(R.string.search_showing_results_for, correction.correctedRuns),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = TextStartPadding),
                )
                correction.originalQuery?.let { original ->
                    TextButton(onClick = { onSearchExactly(original) }) {
                        Text(stringResource(R.string.search_instead_for, original))
                    }
                }
            }

            SearchCorrection.Kind.DID_YOU_MEAN -> {
                TextButton(onClick = { onSearchCorrected(correction.correctedQuery) }) {
                    Text(templated(R.string.search_did_you_mean, correction.correctedRuns))
                }
            }
        }
    }
}

/** The string resource with its `%1$s` replaced by [runs], keeping the corrected words styled. */
@Composable
private fun templated(
    @StringRes template: Int,
    runs: List<SearchCorrectionRun>,
): AnnotatedString {
    val raw = stringResource(template)
    val at = raw.indexOf(PLACEHOLDER)
    return buildAnnotatedString {
        if (at < 0) {
            append(raw)
            return@buildAnnotatedString
        }
        append(raw.substring(0, at))
        runs.forEach { run ->
            if (run.changed) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic, fontWeight = FontWeight.Medium)) { append(run.text) }
            } else {
                append(run.text)
            }
        }
        append(raw.substring(at + PLACEHOLDER.length))
    }
}

private const val PLACEHOLDER = "%1\$s"

private val RowHorizontalPadding = 4.dp

// Puts the text on the TextButton label's start line, 12 dp inside the button.
private val TextStartPadding = 12.dp
