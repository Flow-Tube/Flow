package io.github.aedev.flow.ui.screens.sync.linked

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.profile.ProfileRegistry
import io.github.aedev.flow.sync2.service.LocalProfile
import io.github.aedev.flow.sync2.service.OtherProfile
import io.github.aedev.flow.sync2.service.ProfileChoices
import io.github.aedev.flow.ui.components.shared.FlowNavRow
import io.github.aedev.flow.ui.components.shared.FlowSwitchRow
import io.github.aedev.flow.ui.screens.sync.SyncInfoRow
import io.github.aedev.flow.ui.screens.sync.SyncRowGroup

/** Which profiles sync (spec/protocol.md, Profiles): a switch per profile here, and the synced ones it can add. */
@Composable
internal fun LinkedProfilesCard(
    choices: ProfileChoices,
    busy: Boolean,
    onLink: (profile: String, link: String) -> Unit,
    onCreateFor: (link: String, name: String) -> Unit,
    onStopHere: (profile: String) -> Unit,
    onStopEverywhere: (profile: String) -> Unit,
) {
    var starting by rememberSaveable { mutableStateOf<String?>(null) }
    var stopping by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf<String?>(null) }
    val unnamed = stringResource(R.string.linked_profile_unnamed)
    val defaultName = stringResource(R.string.profile_default_name)
    // No record names the link (an older device synced it); a blank one is a default profile never renamed.
    val label = { other: OtherProfile -> other.name?.ifBlank { defaultName } ?: unnamed }
    val linkedCount = choices.local.count { it.link != null }

    Text(stringResource(R.string.linked_profiles), Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
    if (linkedCount == 0) SyncInfoRow(icon = Icons.Outlined.Info, text = stringResource(R.string.linked_profiles_none))
    SyncRowGroup(count = choices.local.size + choices.others.size) { index, shape ->
        val profile = choices.local.getOrNull(index)
        if (profile != null) {
            FlowSwitchRow(
                title = profile.name,
                checked = profile.link != null,
                onCheckedChange = { on -> if (on) starting = profile.id else stopping = profile.id },
                supportingText =
                    stringResource(if (profile.link != null) R.string.profiles_default_syncs else R.string.linked_profile_local),
                // A device in a group keeps one profile syncing.
                enabled = !busy && !(profile.link != null && linkedCount == 1),
                shape = shape,
            )
        } else {
            val other = choices.others[index - choices.local.size]
            FlowNavRow(
                title = label(other),
                onClick = { adding = other.link },
                supportingText = stringResource(R.string.linked_profile_other),
                enabled = !busy,
                shape = shape,
            )
        }
    }

    choices.local.firstOrNull { it.id == starting }?.let { profile ->
        StartDialog(profile, choices.others, label, onDismiss = { starting = null }) { link ->
            starting = null
            onLink(profile.id, link)
        }
    }
    choices.local.firstOrNull { it.id == stopping }?.let { profile ->
        StopDialog(
            profile = profile,
            onDismiss = { stopping = null },
            onHere = {
                stopping = null
                onStopHere(profile.id)
            },
            onEverywhere = {
                stopping = null
                onStopEverywhere(profile.id)
            },
        )
    }
    choices.others.firstOrNull { it.link == adding }?.let { other ->
        val name = label(other)
        AlertDialog(
            onDismissRequest = { adding = null },
            title = { Text(stringResource(R.string.linked_profile_add_title, name)) },
            text = {
                Text(
                    if (choices.canCreate) {
                        stringResource(R.string.linked_profile_add_body)
                    } else {
                        stringResource(R.string.linked_profile_limit, ProfileRegistry.MAX_PROFILES)
                    },
                )
            },
            confirmButton = {
                if (choices.canCreate) {
                    TextButton(onClick = {
                        adding = null
                        onCreateFor(other.link, name)
                    }) { Text(stringResource(R.string.linked_profile_add)) }
                }
            },
            dismissButton = { TextButton(onClick = { adding = null }) { Text(stringResource(R.string.linked_cancel)) } },
        )
    }
}

/** Starts syncing [profile]: under a link of its own, or with a profile synced from the other devices. */
@Composable
private fun StartDialog(
    profile: LocalProfile,
    others: List<OtherProfile>,
    label: (OtherProfile) -> String,
    onDismiss: () -> Unit,
    onLink: (link: String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.linked_profile_start_title, profile.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (others.isNotEmpty()) Text(stringResource(R.string.linked_profile_merge_note))
                TextButton(onClick = { onLink(profile.id) }) { Text(stringResource(R.string.linked_profile_new_link)) }
                others.forEach { other ->
                    TextButton(
                        onClick = { onLink(other.link) },
                    ) { Text(stringResource(R.string.linked_profile_join, label(other))) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.linked_cancel)) } },
    )
}

/** Stops syncing [profile] here only, or on every device; its data stays everywhere. */
@Composable
private fun StopDialog(
    profile: LocalProfile,
    onDismiss: () -> Unit,
    onHere: () -> Unit,
    onEverywhere: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.linked_profile_stop_title, profile.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.linked_profile_stop_body))
                TextButton(onClick = onHere) { Text(stringResource(R.string.linked_profile_stop_here)) }
                TextButton(onClick = onEverywhere) { Text(stringResource(R.string.linked_profile_stop_everywhere)) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.linked_cancel)) } },
    )
}
