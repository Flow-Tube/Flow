package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

private val HeaderSpacing = 16.dp
private val ButtonSpacing = 8.dp

/** The spacing every Flow dialog shares, including the ones that lay out their own content. */
object FlowDialogDefaults {
    /** Around the dialog's content, on every side but the bottom. */
    val ContentPadding = 24.dp

    /** Below the row of actions, whose buttons already carry their own touch padding. */
    val BottomPadding = 16.dp

    /** Between the content and the row of actions. */
    val ActionsSpacing = 8.dp
}

/**
 * The app's alert dialog: Material 3's `AlertDialog`, the same slots, tokens and button order, with
 * a tighter gap above the actions.
 *
 * Hand-rolled on [BasicAlertDialog] because `AlertDialog` fixes that gap at 24 dp through the
 * internal `AlertDialogDefaults.textPadding`, with no parameter to change it; together with a text
 * button's own padding it left a band of empty space above every row of actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlowAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    BasicAlertDialog(onDismissRequest = onDismissRequest, modifier = modifier, properties = properties) {
        Surface(
            modifier = Modifier.ignoreHeldOk(),
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            Column(
                modifier =
                    Modifier.padding(
                        start = FlowDialogDefaults.ContentPadding,
                        top = FlowDialogDefaults.ContentPadding,
                        end = FlowDialogDefaults.ContentPadding,
                        bottom = FlowDialogDefaults.BottomPadding,
                    ),
            ) {
                icon?.let {
                    ProvideStyle(AlertDialogDefaults.iconContentColor, null) {
                        Box(Modifier.padding(bottom = HeaderSpacing).align(Alignment.CenterHorizontally)) { it() }
                    }
                }
                title?.let {
                    ProvideStyle(AlertDialogDefaults.titleContentColor, MaterialTheme.typography.headlineSmall) {
                        Box(
                            Modifier
                                .padding(bottom = HeaderSpacing)
                                .align(if (icon == null) Alignment.Start else Alignment.CenterHorizontally),
                        ) { it() }
                    }
                }
                text?.let {
                    ProvideStyle(AlertDialogDefaults.textContentColor, MaterialTheme.typography.bodyMedium) {
                        Box(
                            Modifier
                                .weight(weight = 1f, fill = false)
                                .padding(bottom = FlowDialogDefaults.ActionsSpacing)
                                .align(Alignment.Start),
                        ) { it() }
                    }
                }
                Box(modifier = Modifier.align(Alignment.End)) {
                    ProvideStyle(MaterialTheme.colorScheme.primary, MaterialTheme.typography.labelLarge) {
                        DialogButtons(confirmButton = confirmButton, dismissButton = dismissButton)
                    }
                }
            }
        }
    }
}

/** Confirm trails dismiss in a row, but leads it when the row wraps, as `AlertDialog` does. */
@Composable
private fun DialogButtons(
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)?,
) {
    val direction = LocalLayoutDirection.current
    val flipped = if (direction == LayoutDirection.Ltr) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides flipped) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ButtonSpacing),
            verticalArrangement = Arrangement.spacedBy(ButtonSpacing),
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                confirmButton()
                dismissButton?.invoke()
            }
        }
    }
}

@Composable
private fun ProvideStyle(
    color: Color,
    style: TextStyle?,
    content: @Composable () -> Unit,
) {
    val merged = if (style == null) LocalTextStyle.current else LocalTextStyle.current.merge(style)
    CompositionLocalProvider(LocalContentColor provides color, LocalTextStyle provides merged, content = content)
}
