package io.github.aedev.flow.ui.components.shared

import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import io.github.aedev.flow.R
import kotlinx.coroutines.flow.drop

/**
 * The app's one search field: a slim pill carrying the query, its clear button and whatever the
 * surface wants beside them.
 *
 * Built on [BasicTextField] rather than `SearchBarDefaults.InputField` because every overload of
 * that API applies `Modifier.sizeIn(minWidth = 360.dp, minHeight = 56.dp, maxWidth = 720.dp)`
 * internally and exposes no height parameter, so the 40 dp pill this app uses is not reachable
 * through it. Everything else — shape, colours, type, motion — still comes from the theme.
 */
@Composable
fun FlowSearchField(
    state: TextFieldState,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSearch: (String) -> Unit = {},
    onClear: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    onFieldFocused: () -> Unit = {},
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
    releaseFocusWithKeyboard: Boolean = false,
) {
    if (releaseFocusWithKeyboard) ReleaseFocusWhenKeyboardCloses()
    val dpad = LocalDpadNavigation.current
    val view = LocalView.current
    var keyboardRequested by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val shape = MaterialTheme.shapes.extraLarge
    Surface(
        modifier =
            modifier
                .height(PillHeight)
                .then(if (dpad) Modifier.indication(interactionSource, ripple(focusRingShape = shape)) else Modifier),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier =
                Modifier.padding(
                    start = if (leadingIcon != null) PillLeadingPadding else PillStartPadding,
                    end = PillEndPadding,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIcon != null) {
                leadingIcon()
                Spacer(modifier = Modifier.width(PillItemSpacing))
            }

            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (state.text.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    state = state,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                            .onFocusChanged {
                                if (it.isFocused) onFieldFocused() else keyboardRequested = false
                            }
                            // A remote's OK opens the field's input session by turning keyboard-on-focus on while it
                            // holds focus; without a session there is no connection for the keyboard to type into.
                            .then(
                                if (dpad) {
                                    Modifier.onPreviewKeyEvent { event ->
                                        if (event.key != Key.DirectionCenter) return@onPreviewKeyEvent false
                                        if (event.type == KeyEventType.KeyUp) {
                                            if (keyboardRequested) {
                                                view.context.getSystemService<InputMethodManager>()?.showSoftInput(view, 0)
                                            } else {
                                                keyboardRequested = true
                                            }
                                        }
                                        true
                                    }
                                } else {
                                    Modifier
                                },
                            ),
                    interactionSource = interactionSource,
                    textStyle =
                        LocalTextStyle.current.merge(
                            MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        ),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    // A remote moving focus through the field must not raise the keyboard; OK on it does.
                    keyboardOptions =
                        KeyboardOptions(
                            imeAction = ImeAction.Search,
                            showKeyboardOnFocus = if (dpad) keyboardRequested else null,
                        ),
                    onKeyboardAction = { onSearch(state.text.toString()) },
                )
            }

            if (state.text.isNotEmpty()) {
                IconButton(
                    onClick = {
                        state.clearText()
                        onClear?.invoke()
                    },
                    modifier = Modifier.size(ClearButtonSize),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.clear),
                    )
                }
            }

            trailingContent?.invoke(this)
        }
    }
}

/**
 * Overload for surfaces whose query lives in a ViewModel as a [String]. The field still owns a
 * [TextFieldState] internally — the two are kept in step in both directions.
 */
@Composable
fun FlowSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSearch: () -> Unit = {},
    onClear: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    onFieldFocused: () -> Unit = {},
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
    releaseFocusWithKeyboard: Boolean = false,
) {
    val state = remember { TextFieldState(initialText = query) }
    val echoes = remember { QueryEchoFilter() }
    val currentOnQueryChange by rememberUpdatedState(onQueryChange)

    // drop(1) discards snapshotFlow's replay of the text the field was seeded with, so the caller
    // only ever hears about real edits and no surface re-fires its query work on first composition.
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }
            .drop(1)
            .collect {
                echoes.sent(it)
                currentOnQueryChange(it)
            }
    }

    LaunchedEffect(query) {
        if (echoes.shouldApply(query) && query != state.text.toString()) state.setTextAndPlaceCursorAtEnd(query)
    }

    FlowSearchField(
        state = state,
        placeholder = placeholder,
        modifier = modifier,
        onSearch = { onSearch() },
        onClear = onClear,
        focusRequester = focusRequester,
        onFieldFocused = onFieldFocused,
        leadingIcon = leadingIcon,
        trailingContent = trailingContent,
        releaseFocusWithKeyboard = releaseFocusWithKeyboard,
    )
}

/**
 * Tells a late echo of the field's own edit from a query the caller set. A caller whose query comes
 * back through a slow pipeline hands "a" back after the field already holds "ab"; applying it
 * rewinds the text, the field reports "a" again, and the two trade places while the user types.
 */
internal class QueryEchoFilter {
    private val pending = ArrayDeque<String>()

    fun sent(query: String) {
        pending.addLast(query)
        if (pending.size > MAX_PENDING) pending.removeFirst()
    }

    fun shouldApply(query: String): Boolean {
        if (query == pending.lastOrNull()) {
            pending.clear()
            return false
        }
        if (query in pending) return false
        pending.clear()
        return true
    }

    private companion object {
        const val MAX_PENDING = 64
    }
}

/**
 * For a field that filters a list in place: closing the keyboard (back, or the search key) ends
 * editing, so the cursor doesn't stay blinking in a field nobody is typing in.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReleaseFocusWhenKeyboardCloses() {
    val imeVisible = WindowInsets.isImeVisible
    val focusManager = LocalFocusManager.current
    LaunchedEffect(imeVisible) { if (!imeVisible) focusManager.clearFocus() }
}

private val PillHeight = 40.dp
private val PillStartPadding = 16.dp
private val PillLeadingPadding = 12.dp
private val PillEndPadding = 4.dp
private val PillItemSpacing = 8.dp
private val ClearButtonSize = 32.dp
