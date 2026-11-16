package io.github.aedev.flow.ui.components.profiles

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.profile.Profile
import io.github.aedev.flow.data.profile.ProfileRegistry
import io.github.aedev.flow.data.profile.displayName
import io.github.aedev.flow.ui.components.layout.LocalFlowSnackbarHostState
import io.github.aedev.flow.ui.components.settings.SettingsDestination
import io.github.aedev.flow.ui.components.settings.SettingsTarget
import io.github.aedev.flow.ui.components.shared.FlowNavRow
import io.github.aedev.flow.ui.components.shared.FlowRowGroup
import io.github.aedev.flow.ui.components.shared.FlowSectionHeader
import io.github.aedev.flow.ui.components.shared.FlowSegmentedGap
import io.github.aedev.flow.ui.components.shared.flowRowGroupShape
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionRow
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionsGroup
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionsSheet

private val TopBarAvatarSize = 32.dp
private val FooterPadding = 20.dp
private val GroupSpacing = 16.dp

/** Tells why a switch through [viewModel] didn't happen. */
@Composable
fun SwitchRefusals(viewModel: ProfileSwitchViewModel) {
    val refused by viewModel.refused.collectAsStateWithLifecycle()
    val snackbarHostState = LocalFlowSnackbarHostState.current
    val refusedText = refused?.let { stringResource(switchRefusalText(it.reason)) }
    LaunchedEffect(refused) {
        if (refusedText == null) return@LaunchedEffect
        try {
            snackbarHostState?.showSnackbar(refusedText)
        } finally {
            viewModel.consumeRefused()
        }
    }
}

/**
 * The running profile's avatar as a top bar action, opening the way to switch, add or manage profiles. It shows with
 * a single profile too, so the feature can be found.
 */
@Composable
fun ProfileTopBarAction(
    onOpenSettings: (SettingsTarget) -> Unit,
    viewModel: ProfileSwitchViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val running = state?.profiles?.firstOrNull { it.id == viewModel.runningId } ?: return
    var open by rememberSaveable { mutableStateOf(false) }
    val description = stringResource(R.string.profiles_action_description, running.displayName(context))

    IconButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = description }) {
        ProfileAvatar(running, size = TopBarAvatarSize)
    }

    SwitchRefusals(viewModel)

    if (open) {
        ProfileSwitchSheet(
            profiles = state?.profiles.orEmpty(),
            runningId = viewModel.runningId,
            onSwitch = viewModel::switchTo,
            onAdd = { onOpenSettings(SettingsTarget(SettingsDestination.PROFILE_EDIT)) },
            onManage = { onOpenSettings(SettingsTarget(SettingsDestination.PROFILES)) },
            onDismiss = { open = false },
        )
    }
}

@Composable
internal fun ProfileSwitchSheet(
    profiles: List<Profile>,
    runningId: String,
    onSwitch: (String) -> Unit,
    onAdd: () -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val atLimit = profiles.size >= ProfileRegistry.MAX_PROFILES
    QuickActionsSheet(onDismiss = onDismiss) { sheet ->
        FlowSectionHeader(text = stringResource(R.string.profiles_title))
        ProfileDropdown(
            profiles = profiles,
            runningId = runningId,
            onSwitch = { id ->
                sheet.close()
                onSwitch(id)
            },
        )
        Spacer(Modifier.height(GroupSpacing))
        QuickActionsGroup(
            title = null,
            rows =
                listOf(
                    QuickActionRow("profiles.add") { shape ->
                        FlowNavRow(
                            title = stringResource(R.string.profiles_add),
                            supportingText = stringResource(R.string.profiles_limit, ProfileRegistry.MAX_PROFILES).takeIf { atLimit },
                            onClick = {
                                sheet.close()
                                onAdd()
                            },
                            enabled = !atLimit,
                            showChevron = false,
                            leadingIcon = Icons.Outlined.PersonAdd,
                            shape = shape,
                        )
                    },
                    QuickActionRow("profiles.manage") { shape ->
                        FlowNavRow(
                            title = stringResource(R.string.profiles_manage),
                            onClick = {
                                sheet.close()
                                onManage()
                            },
                            leadingIcon = Icons.Outlined.ManageAccounts,
                            shape = shape,
                        )
                    },
                ),
        )
        Text(
            text = stringResource(R.string.profiles_sheet_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(FooterPadding),
        )
    }
}

/**
 * The profile in use, with the others folded beneath it: one card until it is opened, so the sheet stays short
 * however many profiles there are. With a single profile there is nothing to open, and no arrow.
 */
@Composable
private fun ProfileDropdown(
    profiles: List<Profile>,
    runningId: String,
    onSwitch: (String) -> Unit,
) {
    val context = LocalContext.current
    val running = profiles.firstOrNull { it.id == runningId } ?: return
    val others = profiles.filterNot { it.id == runningId }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = expanded
    // The first card joins the rows below only while they are on screen, including while they fold away.
    val joined = visibility.currentState || visibility.targetState
    val sizeSpec = MaterialTheme.motionScheme.defaultEffectsSpec<IntSize>()
    val fadeSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()

    FlowRowGroup {
        Column {
            FlowNavRow(
                title = running.displayName(context),
                supportingText = stringResource(R.string.profiles_in_use),
                onClick = { if (others.isNotEmpty()) expanded = !expanded },
                selected = true,
                showChevron = false,
                leadingContent = { ProfileAvatar(running) },
                trailingContent =
                    if (others.isEmpty()) {
                        null
                    } else {
                        {
                            Icon(
                                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription =
                                    stringResource(
                                        if (expanded) R.string.profiles_hide_others else R.string.profiles_show_others,
                                    ),
                            )
                        }
                    },
                shape = flowRowGroupShape(index = 0, count = if (joined) profiles.size else 1),
            )
            AnimatedVisibility(
                visibleState = visibility,
                enter = expandVertically(sizeSpec) + fadeIn(fadeSpec),
                exit = shrinkVertically(sizeSpec) + fadeOut(fadeSpec),
            ) {
                Column(
                    modifier = Modifier.padding(top = FlowSegmentedGap),
                    verticalArrangement = Arrangement.spacedBy(FlowSegmentedGap),
                ) {
                    others.forEachIndexed { index, profile ->
                        FlowNavRow(
                            title = profile.displayName(context),
                            onClick = { onSwitch(profile.id) },
                            showChevron = false,
                            leadingContent = { ProfileAvatar(profile) },
                            shape = flowRowGroupShape(index = index + 1, count = profiles.size),
                        )
                    }
                }
            }
        }
    }
}
