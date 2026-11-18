package io.github.aedev.flow.ui.tv.player

import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.shared.FlowErrorState
import io.github.aedev.flow.ui.components.shared.FlowLoadingIndicator

private val ErrorMaxWidth = 560.dp

/**
 * The spinner while a video loads with the controls hidden, or the load error with a retry, and a
 * session reset when one may get past the error. [errorFocusRequester] moves the remote onto the
 * error's buttons.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun TvPlayerStatus(
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onResetSession: (() -> Unit)?,
    errorFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    if (loading) FlowLoadingIndicator(modifier = modifier)
    if (error != null) {
        Surface(
            modifier = modifier.widthIn(max = ErrorMaxWidth).focusRequester(errorFocusRequester),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            FlowErrorState(
                error = error,
                onRetry = onRetry,
                secondaryAction =
                    onResetSession?.let { reset ->
                        {
                            OutlinedButton(onClick = reset, shapes = ButtonDefaults.shapes()) {
                                Text(stringResource(R.string.diagnostics_reset_session))
                            }
                        }
                    },
            )
        }
    }
}
