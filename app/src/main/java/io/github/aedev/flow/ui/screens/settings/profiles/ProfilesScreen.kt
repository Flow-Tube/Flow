package io.github.aedev.flow.ui.screens.settings.profiles

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.profile.Profile
import io.github.aedev.flow.data.profile.ProfileRegistry
import io.github.aedev.flow.data.profile.displayName
import io.github.aedev.flow.ui.components.profiles.ProfileAvatar
import io.github.aedev.flow.ui.components.settings.SettingsDestination
import io.github.aedev.flow.ui.components.settings.SettingsPage
import io.github.aedev.flow.ui.components.settings.SettingsTarget
import io.github.aedev.flow.ui.components.settings.nav
import io.github.aedev.flow.ui.components.settings.notice
import io.github.aedev.flow.ui.components.settings.switch
import io.github.aedev.flow.ui.screens.settings.index.ProfilesIndex

private sealed interface ProfileAction {
    val id: String

    data class Rename(
        override val id: String,
    ) : ProfileAction

    data class Delete(
        override val id: String,
    ) : ProfileAction
}

/** Every profile on this device: switch to one, edit, rename or delete it, or add another, up to five. */
@Composable
internal fun ProfilesScreen(
    onBack: (() -> Unit)?,
    highlight: String?,
    onNavigate: (SettingsTarget) -> Unit,
    viewModel: ProfilesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val synced by viewModel.synced.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var action by remember { mutableStateOf<ProfileAction?>(null) }
    val profiles = state?.profiles.orEmpty()
    val atLimit = profiles.size >= ProfileRegistry.MAX_PROFILES

    ProfilesMessageEffect(message, snackbarHostState, viewModel::consumeMessage)

    SettingsPage(
        title = stringResource(R.string.profiles_title),
        onBack = onBack,
        highlight = highlight,
        snackbarHostState = snackbarHostState,
    ) {
        group(key = "profiles.list") {
            profiles.forEach { profile ->
                row("profiles.${profile.id}") { shape ->
                    ProfileRow(
                        profile = profile,
                        running = profile.id == viewModel.runningId,
                        isDefault = profile.id == state?.defaultId,
                        syncs = profile.id in synced,
                        enabled = !busy,
                        shape = shape,
                        onEdit = { onNavigate(SettingsTarget(SettingsDestination.PROFILE_EDIT, tab = profile.id)) },
                        onSwitch = { viewModel.switchTo(profile.id) },
                        onRename = { action = ProfileAction.Rename(profile.id) },
                        onDelete = { action = ProfileAction.Delete(profile.id) },
                    )
                }
            }
        }
        group(key = "profiles.actions") {
            nav(
                ProfilesIndex.add,
                icon = Icons.Outlined.PersonAdd,
                showChevron = false,
                enabled = !atLimit && !busy,
                valueText = { if (atLimit) stringResource(R.string.profiles_limit, ProfileRegistry.MAX_PROFILES) else null },
                onClick = { onNavigate(SettingsTarget(SettingsDestination.PROFILE_EDIT)) },
            )
            switch(
                ProfilesIndex.askWhosWatching,
                checked = state?.askWhosWatching ?: true,
                onCheckedChange = viewModel::setAskWhosWatching,
            )
        }
        if (profiles.isNotEmpty()) {
            notice("profiles.count", text = {
                stringResource(R.string.profiles_count, profiles.size, ProfileRegistry.MAX_PROFILES) + "\n\n" +
                    stringResource(R.string.profiles_footer)
            })
        }
    }

    val context = LocalContext.current
    val target = profiles.firstOrNull { it.id == action?.id }
    when (val current = action) {
        is ProfileAction.Rename -> {
            if (target != null) {
                RenameProfileDialog(
                    initialName = target.name,
                    otherNames = profiles.filterNot { it.id == target.id }.map { it.displayName(context) },
                    allowBlank = target.id == state?.defaultId,
                    onConfirm = { name -> viewModel.rename(current.id, name) },
                    onDismiss = { action = null },
                )
            }
        }

        is ProfileAction.Delete -> {
            if (target != null) {
                DeleteProfileDialog(
                    name = target.displayName(context),
                    onConfirm = { viewModel.delete(current.id) },
                    onDismiss = { action = null },
                )
            }
        }

        null -> {
            Unit
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProfileRow(
    profile: Profile,
    running: Boolean,
    isDefault: Boolean,
    syncs: Boolean,
    enabled: Boolean,
    shape: Shape,
    onEdit: () -> Unit,
    onSwitch: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val name = profile.displayName(LocalContext.current)
    var menuOpen by remember { mutableStateOf(false) }
    val status =
        listOfNotNull(
            stringResource(R.string.profiles_in_use).takeIf { running },
            stringResource(R.string.profiles_default_syncs).takeIf { syncs },
        ).joinToString(" · ")

    SegmentedListItem(
        onClick = onEdit,
        enabled = enabled,
        selected = running,
        verticalAlignment = Alignment.CenterVertically,
        shapes = ListItemDefaults.shapes(shape = shape),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        leadingContent = { ProfileAvatar(profile) },
        supportingContent = status.takeIf { it.isNotEmpty() }?.let { { Text(it) } },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = enabled) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.profiles_more, name))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    listOfNotNull(
                        (R.string.profiles_switch to onSwitch).takeUnless { running },
                        R.string.profiles_edit to onEdit,
                        R.string.profiles_rename to onRename,
                        (R.string.profiles_delete to onDelete).takeUnless { running || isDefault },
                    ).forEach { (label, onClick) ->
                        DropdownMenuItem(
                            text = { Text(stringResource(label)) },
                            onClick = {
                                menuOpen = false
                                onClick()
                            },
                        )
                    }
                }
            }
        },
    ) {
        Text(name)
    }
}
