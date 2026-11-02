package io.github.aedev.flow.ui.components.music.sheet

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R
import io.github.aedev.flow.data.music.model.MusicArtist
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.ui.components.layout.navigation.LocalMediaNavigator
import io.github.aedev.flow.ui.components.shared.ChannelAvatarStack
import io.github.aedev.flow.ui.components.shared.FlowNavRow
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionRow
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionsDefaults
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionsGroup
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionsPageHeader
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionsSheet
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionsViewModel
import io.github.aedev.flow.ui.components.shared.quickactions.sharedQuickActionsViewModel

/** The song's artists with ids, or its channel when the source named no artist. */
internal fun MusicTrack.menuArtists(): List<MusicArtist> =
    artists.filter { !it.id.isNullOrBlank() }.ifEmpty {
        if (channelId.isNotBlank()) listOf(MusicArtist(name = artist, id = channelId)) else emptyList()
    }

/** One row per credited artist, each opening that artist's page. */
@Composable
internal fun MusicArtistsPage(
    artists: List<MusicArtist>,
    avatars: Map<String, String>,
    onBack: (() -> Unit)?,
    onClose: () -> Unit,
) {
    QuickActionsPageHeader(title = stringResource(R.string.quick_action_artists), onBack = onBack, onClose = onClose)
    QuickActionsGroup(title = null, rows = artists.map { artistRow(it, listOfNotNull(avatars[it.id]), onClose) })
}

/** The song menu's Artists page on its own, for a song credited to several artists. */
@Composable
internal fun MusicArtistsSheet(
    artists: List<MusicArtist>,
    onDismiss: () -> Unit,
    quickActions: QuickActionsViewModel = sharedQuickActionsViewModel(),
) {
    val avatars by produceState(emptyMap<String, String>(), artists) {
        value = quickActions.channelAvatars(artists.mapNotNull { it.id })
    }
    QuickActionsSheet(onDismiss = onDismiss) { sheet ->
        MusicArtistsPage(artists = artists, avatars = avatars, onBack = null, onClose = sheet::close)
    }
}

@Composable
private fun artistRow(
    artist: MusicArtist,
    avatar: List<String>,
    onDismiss: () -> Unit,
): QuickActionRow {
    val navigator = LocalMediaNavigator.current
    val ringColor = MaterialTheme.colorScheme.surfaceContainerHigh
    return QuickActionRow(artist.id.orEmpty()) { shape ->
        FlowNavRow(
            title = artist.name,
            onClick = {
                navigator.openArtist(artist.id.orEmpty())
                onDismiss()
            },
            shape = shape,
            leadingContent = {
                ChannelAvatarStack(
                    urls = avatar,
                    contentDescription = null,
                    avatarSize = QuickActionsDefaults.AvatarSize,
                    ringColor = ringColor,
                )
            },
            trailingContent = { Icon(imageVector = Icons.Outlined.ChevronRight, contentDescription = null) },
        )
    }
}
