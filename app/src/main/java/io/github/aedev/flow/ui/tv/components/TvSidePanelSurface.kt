package io.github.aedev.flow.ui.tv.components

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens
import kotlinx.coroutines.flow.filter

/**
 * TV's side panel, its replacement for bottom sheets: a full-height surface on the end edge and a focus
 * trap, so no D-pad direction leaves it. Its content takes focus when it opens and again whenever focus
 * drops out of it (a reload, a content swap) while [keepsFocus] holds. [title] null leaves the header to
 * the content, as the shared sheets draw their own.
 */
@Composable
internal fun TvSidePanelSurface(
    title: String?,
    onClose: (() -> Unit)?,
    keepsFocus: () -> Boolean,
    modifier: Modifier = Modifier,
    width: Dp = LocalTvDimens.current.sidePanelWidth,
    contentPadding: PaddingValues = PaddingValues(),
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dimens = LocalTvDimens.current
    val contentFocusRequester = remember { FocusRequester() }
    var panelHasFocus by remember { mutableStateOf(false) }

    Surface(
        modifier =
            modifier
                .width(width)
                .fillMaxHeight(),
        shape = MaterialTheme.shapes.extraLarge.copy(topEnd = ZeroCornerSize, bottomEnd = ZeroCornerSize),
        color = color,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .onFocusChanged { panelHasFocus = it.hasFocus }
                    .focusProperties { onExit = { cancelFocusChange() } }
                    .focusGroup()
                    .padding(vertical = dimens.overscanVertical),
        ) {
            if (title != null || onClose != null) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title.orEmpty(),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    onClose?.let {
                        TvIconButton(
                            icon = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.close),
                            onClick = it,
                        )
                    }
                }
            }
            // Focus lands on the content's first row, not the close button.
            Box(
                Modifier
                    .weight(1f)
                    .focusRequester(contentFocusRequester)
                    .focusGroup()
                    .padding(contentPadding),
            ) {
                Column(content = content)
            }
        }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { !panelHasFocus && keepsFocus() }
            .filter { it }
            .collect { contentFocusRequester.requestFocus() }
    }
}
