package io.github.aedev.flow.ui.screens.settings.integrations

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.SponsorBlockAction
import io.github.aedev.flow.data.local.SponsorBlockSkipButtonStyle
import io.github.aedev.flow.data.model.SponsorBlockCategories
import io.github.aedev.flow.ui.components.settings.SettingsListScope
import io.github.aedev.flow.ui.components.settings.choice
import io.github.aedev.flow.ui.components.settings.switch
import io.github.aedev.flow.ui.components.shared.FlowChoice
import io.github.aedev.flow.ui.components.shared.FlowChoiceDialog
import io.github.aedev.flow.ui.components.shared.FlowNavRow
import io.github.aedev.flow.ui.screens.settings.index.IntegrationsIndex
import io.github.aedev.flow.ui.theme.defaultSponsorBlockColor
import io.github.aedev.flow.utils.sponsorCategoryLabelRes

private val SwatchSize = 24.dp
private val SwatchBorder = 1.dp
private const val USER_ID_PREVIEW_LENGTH = 8

internal fun SettingsListScope.sponsorBlockSection(
    viewModel: IntegrationsViewModel,
    enabled: Boolean,
    submitButton: Boolean,
    userId: String?,
    segments: Map<String, SegmentSetting>,
    skipButtonStyle: SponsorBlockSkipButtonStyle,
    onPickSkipButtonStyle: () -> Unit,
    onEditUserId: () -> Unit,
    onPickColour: (String) -> Unit,
) {
    group(key = "integrations.sponsorblock.group", header = R.string.player_settings_sponsorblock) {
        switch(IntegrationsIndex.sponsorBlock, viewModel.sponsorBlock, viewModel::setSponsorBlock, iconRes = R.drawable.ic_block)
        switch(IntegrationsIndex.skipNotice, viewModel.skipNotice, viewModel::setSkipNotice, enabled = enabled)
        choice(IntegrationsIndex.skipButtonStyle, onClick = onPickSkipButtonStyle, enabled = enabled) {
            stringResource(skipButtonStyleLabel(skipButtonStyle))
        }
        switch(IntegrationsIndex.contribute, viewModel.submitButton, viewModel::setSubmitButton, enabled = enabled)
        if (submitButton) {
            row(IntegrationsIndex.userId.key) { shape ->
                FlowNavRow(
                    title = stringResource(IntegrationsIndex.userId.title),
                    supportingText =
                        userId?.let { stringResource(R.string.settings_truncated, it.take(USER_ID_PREVIEW_LENGTH)) }
                            ?: stringResource(R.string.sb_user_id_not_set),
                    enabled = enabled,
                    onClick = onEditUserId,
                    shape = shape,
                )
            }
        }
    }
    if (enabled) {
        group(key = IntegrationsIndex.segments.key, header = R.string.sb_segments_header) {
            SponsorBlockCategories.all.forEach { category ->
                row("integrations.segment.$category") { shape ->
                    SegmentRow(
                        category = category,
                        setting = segments[category],
                        shape = shape,
                        onActionSelected = { viewModel.setSegmentAction(category, it) },
                        onPickColour = { onPickColour(category) },
                    )
                }
            }
        }
    }
}

@StringRes
internal fun skipButtonStyleLabel(style: SponsorBlockSkipButtonStyle): Int =
    when (style) {
        SponsorBlockSkipButtonStyle.FULL -> R.string.sb_skip_style_full
        SponsorBlockSkipButtonStyle.COMPACT -> R.string.sb_skip_style_compact
        SponsorBlockSkipButtonStyle.ICON_ONLY -> R.string.sb_skip_style_icon
    }

/**
 * One segment category: its colour on the seek bar (tap to change) and what the player does when it
 * reaches one (tap the row to choose).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SegmentRow(
    category: String,
    setting: SegmentSetting?,
    shape: Shape,
    onActionSelected: (SponsorBlockAction) -> Unit,
    onPickColour: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val label = sponsorCategoryLabelRes(category)?.let { stringResource(it) } ?: category
    val colour = setting?.colorArgb?.let(::Color) ?: defaultSponsorBlockColor(category)
    val action = setting?.action ?: SponsorBlockCategories.defaultAction(category)

    SegmentedListItem(
        verticalAlignment = Alignment.CenterVertically,
        onClick = { menuOpen = true },
        shapes = ListItemDefaults.shapes(shape = shape),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        leadingContent = {
            IconButton(onClick = onPickColour) {
                Box(
                    modifier =
                        Modifier
                            .size(SwatchSize)
                            .clip(CircleShape)
                            .background(colour)
                            .border(SwatchBorder, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                )
            }
        },
        supportingContent = { Text(segmentActionLabel(category, action)) },
    ) {
        Text(label)
    }
    if (menuOpen) {
        FlowChoiceDialog(
            title = label,
            options =
                SponsorBlockCategories.actionsFor(category).map {
                    FlowChoice(it, segmentActionLabel(category, it), segmentActionDetail(category, it))
                },
            selected = action,
            onSelect = onActionSelected,
            onDismiss = { menuOpen = false },
        )
    }
}

@Composable
private fun segmentActionLabel(
    category: String,
    action: SponsorBlockAction,
): String =
    if (category == SponsorBlockCategories.HIGHLIGHT && action == SponsorBlockAction.SKIP_BUTTON) {
        stringResource(R.string.sb_action_jump_button)
    } else {
        sponsorActionLabel(action)
    }

@Composable
private fun segmentActionDetail(
    category: String,
    action: SponsorBlockAction,
): String? =
    if (category == SponsorBlockCategories.HIGHLIGHT && action == SponsorBlockAction.SKIP_BUTTON) {
        stringResource(R.string.sb_action_jump_button_detail)
    } else {
        sponsorActionDetail(action)
    }

@Composable
private fun sponsorActionLabel(action: SponsorBlockAction): String =
    when (action) {
        SponsorBlockAction.SKIP -> stringResource(R.string.sb_action_skip_automatically)
        SponsorBlockAction.SKIP_ONCE -> stringResource(R.string.sb_action_skip_once)
        SponsorBlockAction.SKIP_BUTTON -> stringResource(R.string.sb_action_skip_button)
        SponsorBlockAction.SHOW_TOAST -> stringResource(R.string.sb_action_notify)
        SponsorBlockAction.MUTE -> stringResource(R.string.sb_action_mute)
        SponsorBlockAction.IGNORE -> stringResource(R.string.sb_action_seek_bar_only)
        SponsorBlockAction.DISABLED -> stringResource(R.string.sb_action_disable)
    }

@Composable
private fun sponsorActionDetail(action: SponsorBlockAction): String? =
    when (action) {
        SponsorBlockAction.SKIP_ONCE -> stringResource(R.string.sb_action_skip_once_detail)
        SponsorBlockAction.SHOW_TOAST, SponsorBlockAction.MUTE -> stringResource(R.string.sb_action_with_button_detail)
        else -> null
    }
