package io.github.aedev.flow.ui.components.shared

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R

/** The note and group items a channel menu shows; null hides an item. */
@Immutable
class ChannelOrganizeActions(
    val onAddNote: (() -> Unit)?,
    val onManageGroups: (() -> Unit)?,
) {
    companion object {
        val None = ChannelOrganizeActions(onAddNote = null, onManageGroups = null)
    }
}

/** Null outside the app shell, so a menu drawn on its own (a test, a preview) shows neither item. */
val LocalChannelOrganizer = staticCompositionLocalOf<ChannelOrganizeViewModel?> { null }

/** Gives every channel menu below the activity's organizer and draws the dialogs it asks for. */
@Composable
fun ProvideChannelOrganizer(content: @Composable () -> Unit) {
    val organizer = sharedChannelOrganizer()
    CompositionLocalProvider(LocalChannelOrganizer provides organizer) {
        content()
        ChannelOrganizeHost(organizer)
    }
}

/**
 * Add note follows the channel notes switch. Add to group needs a subscription, since groups only
 * sort the subscription feed. A null [target] (a music artist) has neither. [beforeOpen] runs first,
 * for a sheet that should close under the dialog.
 */
@Composable
fun rememberChannelOrganizeActions(
    target: ChannelOrganizeTarget?,
    isSubscribed: Boolean,
    beforeOpen: () -> Unit = {},
): ChannelOrganizeActions {
    val organizer = LocalChannelOrganizer.current ?: return ChannelOrganizeActions.None
    val notesEnabled by organizer.notesEnabled.collectAsStateWithLifecycle()
    val currentBeforeOpen by rememberUpdatedState(beforeOpen)
    return remember(target, isSubscribed, notesEnabled, organizer) {
        val channel = target?.takeIf { it.channelId.isNotBlank() }
        ChannelOrganizeActions(
            onAddNote =
                if (channel != null && notesEnabled) {
                    {
                        currentBeforeOpen()
                        organizer.editNote(channel)
                    }
                } else {
                    null
                },
            onManageGroups =
                if (channel != null && isSubscribed) {
                    {
                        currentBeforeOpen()
                        organizer.manageGroups(channel)
                    }
                } else {
                    null
                },
        )
    }
}

@Composable
private fun ChannelOrganizeHost(organizer: ChannelOrganizeViewModel) {
    val request by organizer.request.collectAsStateWithLifecycle()
    val current = request ?: return
    val channelId = current.target.channelId
    when (current.kind) {
        ChannelOrganizeKind.Note -> {
            FlowNoteEditorDialog(
                initialText = current.noteText,
                title = stringResource(R.string.note_channel_title),
                onSave = organizer::saveNote,
                onDismiss = organizer::dismiss,
            )
        }

        ChannelOrganizeKind.Groups -> {
            val groups by organizer.groups.collectAsStateWithLifecycle()
            ChannelGroupSheet(
                groups = groups,
                channelId = channelId,
                onToggle = organizer::setInGroup,
                onCreateNew = organizer::startNewGroup,
                onDismiss = organizer::dismiss,
            )
        }

        ChannelOrganizeKind.NewGroup -> {
            CollectionEditDialog(
                title = stringResource(R.string.new_group),
                confirmLabel = stringResource(R.string.save),
                onDismiss = organizer::dismiss,
                onConfirm = { name, _ ->
                    organizer.createGroup(name)
                    organizer.dismiss()
                },
                icon = Icons.Rounded.Folder,
                showDescription = false,
            )
        }
    }
}
