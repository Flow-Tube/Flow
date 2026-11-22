package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import io.github.aedev.flow.R
import io.github.aedev.flow.utils.LoadErrorKind

/** The tail of any paged feed: loading, a retry, or the end of the results. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FeedPagingFooter(
    appendState: LoadState,
    itemCount: Int,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        appendState is LoadState.Loading -> {
            FlowFeedProgress(modifier)
        }

        appendState is LoadState.Error -> {
            val kind = LoadErrorKind.of(appendState.error)
            Column(
                modifier = modifier.fillMaxWidth().padding(FooterPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(ErrorSpacing),
            ) {
                Text(
                    text = stringResource(kind.messageRes(R.string.load_failed)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
                if (kind == LoadErrorKind.OFFLINE) {
                    Text(
                        text = stringResource(R.string.load_error_offline_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                OutlinedButton(onClick = onRetry, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.retry))
                }
            }
        }

        appendState.endOfPaginationReached && itemCount > 0 -> {
            Box(modifier.fillMaxWidth().padding(FooterPadding), Alignment.Center) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(EndSpacing),
                ) {
                    HorizontalDivider(Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.end_of_results),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider(Modifier.weight(1f))
                }
            }
        }
    }
}

private val FooterPadding = 20.dp
private val ErrorSpacing = 8.dp
private val EndSpacing = 8.dp
