package io.github.aedev.flow.ui.components.musicplayer.lyrics

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Abc
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FontDownload
import androidx.compose.material.icons.outlined.FormatAlignCenter
import androidx.compose.material.icons.outlined.FormatAlignLeft
import androidx.compose.material.icons.outlined.FormatAlignRight
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.LYRICS_ALIGN_CENTER
import io.github.aedev.flow.data.local.LYRICS_ALIGN_LEFT
import io.github.aedev.flow.data.local.LYRICS_ALIGN_RIGHT
import io.github.aedev.flow.data.local.LyricsBackgroundStyle
import io.github.aedev.flow.ui.components.shared.FlowAlertDialog
import io.github.aedev.flow.ui.components.shared.FlowChoice
import io.github.aedev.flow.ui.components.shared.FlowChoiceDialog
import io.github.aedev.flow.ui.components.shared.FlowNavRow
import io.github.aedev.flow.ui.components.shared.FlowSectionHeader
import io.github.aedev.flow.ui.components.shared.FlowSwitchRow
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionRow
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionsGroup
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionsSheet
import java.util.Locale

@Composable
internal fun LyricsActionsSheet(
    hasLyrics: Boolean,
    providerName: String,
    alignPref: String,
    syncOffsetMs: Long,
    display: LyricsDisplayOptions,
    japaneseLyrics: Boolean,
    hasTranslation: Boolean,
    onRefresh: () -> Unit,
    onChooseSource: (() -> Unit)?,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onSaveFile: () -> Unit,
    onAlignChange: (String) -> Unit,
    onDisplayChange: (LyricsDisplayOptions) -> Unit,
    onAdjustSync: () -> Unit,
    onDismiss: () -> Unit,
) {
    var showBackgroundChoice by remember { mutableStateOf(false) }
    if (showBackgroundChoice) {
        FlowChoiceDialog(
            title = stringResource(R.string.lyrics_background),
            options =
                LyricsBackgroundStyle.entries.map {
                    FlowChoice(it, stringResource(lyricsBackgroundLabel(it)), stringResource(lyricsBackgroundDetail(it)))
                },
            selected = display.background,
            onSelect = { onDisplayChange(display.copy(background = it)) },
            onDismiss = { showBackgroundChoice = false },
        )
    }
    QuickActionsSheet(onDismiss = onDismiss) { sheet ->
        fun run(action: () -> Unit): () -> Unit =
            {
                sheet.hideThen {
                    onDismiss()
                    action()
                }
            }
        QuickActionsGroup(
            title = null,
            rows =
                buildList {
                    add(
                        lyricsRow(
                            key = "refresh",
                            icon = Icons.Outlined.Refresh,
                            title = stringResource(R.string.refresh_lyrics),
                            supporting =
                                providerName.takeIf { it.isNotBlank() }?.let {
                                    stringResource(
                                        R.string.lyrics_current_source,
                                        it,
                                    )
                                },
                            onClick = run(onRefresh),
                        ),
                    )
                    if (onChooseSource != null) {
                        add(
                            lyricsRow(
                                "source",
                                Icons.Outlined.TravelExplore,
                                stringResource(R.string.lyrics_choose_source),
                                onClick = run(onChooseSource),
                            ),
                        )
                    }
                    if (hasLyrics) {
                        add(lyricsRow("edit", Icons.Outlined.Edit, stringResource(R.string.lyrics_edit), onClick = run(onEdit)))
                        add(lyricsRow("copy", Icons.Outlined.ContentCopy, stringResource(R.string.lyrics_copy), onClick = run(onCopy)))
                        add(lyricsRow("save", Icons.Outlined.SaveAlt, stringResource(R.string.lyrics_save_file), onClick = run(onSaveFile)))
                    }
                },
        )
        FlowSectionHeader(stringResource(R.string.lyrics_display_header))
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(44.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LyricsAlignToggleButton(
                checked = alignPref == LYRICS_ALIGN_LEFT,
                icon = Icons.Outlined.FormatAlignLeft,
                contentDescription = stringResource(R.string.lyrics_align_left),
                uncheckedShape =
                    RoundedCornerShape(
                        topStart = 22.dp,
                        bottomStart = 22.dp,
                        topEnd = 8.dp,
                        bottomEnd = 8.dp,
                    ),
                onClick = { onAlignChange(LYRICS_ALIGN_LEFT) },
            )
            LyricsAlignToggleButton(
                checked = alignPref == LYRICS_ALIGN_CENTER,
                icon = Icons.Outlined.FormatAlignCenter,
                contentDescription = stringResource(R.string.lyrics_align_center),
                uncheckedShape = RoundedCornerShape(8.dp),
                onClick = { onAlignChange(LYRICS_ALIGN_CENTER) },
            )
            LyricsAlignToggleButton(
                checked = alignPref == LYRICS_ALIGN_RIGHT,
                icon = Icons.Outlined.FormatAlignRight,
                contentDescription = stringResource(R.string.lyrics_align_right),
                uncheckedShape =
                    RoundedCornerShape(
                        topStart = 8.dp,
                        bottomStart = 8.dp,
                        topEnd = 22.dp,
                        bottomEnd = 22.dp,
                    ),
                onClick = { onAlignChange(LYRICS_ALIGN_RIGHT) },
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        QuickActionsGroup(
            title = null,
            rows =
                listOf(
                    lyricsSwitchRow(
                        key = "translation",
                        icon = Icons.Outlined.Translate,
                        title = stringResource(R.string.lyrics_show_translation),
                        supporting =
                            stringResource(
                                if (hasTranslation) R.string.lyrics_show_translation_summary else R.string.lyrics_no_translation,
                            ),
                        checked = hasTranslation && display.showTranslation,
                        enabled = hasTranslation,
                        onCheckedChange = { onDisplayChange(display.copy(showTranslation = it)) },
                    ),
                    lyricsSwitchRow(
                        key = "romanization",
                        icon = Icons.Outlined.Abc,
                        title = stringResource(R.string.lyrics_show_romanization),
                        supporting = stringResource(R.string.lyrics_show_romanization_summary),
                        checked = display.showRomanization,
                        onCheckedChange = { onDisplayChange(display.copy(showRomanization = it)) },
                    ),
                ) +
                    listOfNotNull(
                        lyricsSwitchRow(
                            key = "auto_romanize",
                            icon = Icons.Outlined.AutoAwesome,
                            title = stringResource(R.string.lyrics_auto_romanize),
                            supporting =
                                stringResource(
                                    if (japaneseLyrics) R.string.lyrics_auto_romanize_japanese else R.string.lyrics_auto_romanize_summary,
                                ),
                            checked = display.autoRomanize,
                            enabled = display.showRomanization,
                            onCheckedChange = { onDisplayChange(display.copy(autoRomanize = it)) },
                        ).takeIf { LyricsRomanizer.isAvailable },
                        lyricsSwitchRow(
                            key = "romanization_only",
                            icon = Icons.Outlined.FontDownload,
                            title = stringResource(R.string.lyrics_romanization_only),
                            supporting = stringResource(R.string.lyrics_romanization_only_summary),
                            checked = display.romanizationOnly,
                            enabled = display.showRomanization,
                            onCheckedChange = { onDisplayChange(display.copy(romanizationOnly = it)) },
                        ),
                    ) +
                    lyricsSwitchRow(
                        key = "keep_screen_on",
                        icon = Icons.Outlined.LightMode,
                        title = stringResource(R.string.lyrics_keep_screen_on),
                        supporting = stringResource(R.string.lyrics_keep_screen_on_summary),
                        checked = display.keepScreenOn,
                        onCheckedChange = { onDisplayChange(display.copy(keepScreenOn = it)) },
                    ) +
                    lyricsSwitchRow(
                        key = "blur_other_lines",
                        icon = Icons.Outlined.BlurOn,
                        title = stringResource(R.string.lyrics_blur_other_lines),
                        supporting = stringResource(R.string.lyrics_blur_other_lines_summary),
                        checked = display.blurOtherLines,
                        onCheckedChange = { onDisplayChange(display.copy(blurOtherLines = it)) },
                    ) +
                    lyricsRow(
                        key = "background",
                        icon = Icons.Outlined.Wallpaper,
                        title = stringResource(R.string.lyrics_background),
                        supporting = stringResource(lyricsBackgroundLabel(display.background)),
                        onClick = { showBackgroundChoice = true },
                    ),
        )
        Spacer(modifier = Modifier.height(12.dp))
        QuickActionsGroup(
            title = null,
            rows =
                listOf(
                    lyricsRow(
                        key = "sync",
                        icon = Icons.Outlined.Timer,
                        title = stringResource(R.string.lyrics_adjust_sync),
                        supporting =
                            syncOffsetMs.takeIf { it != 0L }?.let { offset ->
                                stringResource(R.string.lyrics_sync_offset_value, String.format(Locale.US, "%+.1f", offset / 1000f))
                            },
                        onClick = run(onAdjustSync),
                    ),
                ),
        )
    }
}

@StringRes
private fun lyricsBackgroundLabel(style: LyricsBackgroundStyle): Int =
    when (style) {
        LyricsBackgroundStyle.SOLID -> R.string.lyrics_background_solid
        LyricsBackgroundStyle.COLORFUL -> R.string.lyrics_background_colorful
        LyricsBackgroundStyle.MOVING -> R.string.lyrics_background_moving
    }

@StringRes
private fun lyricsBackgroundDetail(style: LyricsBackgroundStyle): Int =
    when (style) {
        LyricsBackgroundStyle.SOLID -> R.string.lyrics_background_solid_detail
        LyricsBackgroundStyle.COLORFUL -> R.string.lyrics_background_colorful_detail
        LyricsBackgroundStyle.MOVING -> R.string.lyrics_background_moving_detail
    }

private fun lyricsRow(
    key: String,
    icon: ImageVector,
    title: String,
    supporting: String? = null,
    onClick: () -> Unit,
): QuickActionRow =
    QuickActionRow(key) { shape ->
        FlowNavRow(title = title, supportingText = supporting, leadingIcon = icon, onClick = onClick, showChevron = false, shape = shape)
    }

private fun lyricsSwitchRow(
    key: String,
    icon: ImageVector,
    title: String,
    supporting: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
): QuickActionRow =
    QuickActionRow(key) { shape ->
        FlowSwitchRow(
            title = title,
            checked = checked,
            onCheckedChange = onCheckedChange,
            supportingText = supporting,
            leadingIcon = icon,
            enabled = enabled,
            shape = shape,
        )
    }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RowScope.LyricsAlignToggleButton(
    checked: Boolean,
    icon: ImageVector,
    contentDescription: String,
    uncheckedShape: RoundedCornerShape,
    onClick: () -> Unit,
) {
    ToggleButton(
        checked = checked,
        onCheckedChange = { onClick() },
        modifier =
            Modifier
                .weight(1f)
                .fillMaxHeight(),
        colors =
            ToggleButtonDefaults.toggleButtonColors(
                containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                checkedContainerColor = MaterialTheme.colorScheme.primary,
                checkedContentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        shapes =
            ToggleButtonShapes(
                shape = uncheckedShape,
                pressedShape = RoundedCornerShape(12.dp),
                checkedShape = RoundedCornerShape(22.dp),
            ),
        contentPadding = PaddingValues(0.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
internal fun LyricsEditDialog(
    initialText: String,
    onApply: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(initialText) { mutableStateOf(initialText) }
    FlowAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lyrics_edit)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(320.dp),
                textStyle = MaterialTheme.typography.bodySmall,
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onApply(text)
                    onDismiss()
                },
                enabled = text.isNotBlank(),
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
