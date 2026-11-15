package io.github.aedev.flow.ui.screens.sync

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R
import io.github.aedev.flow.sync.protocol.SyncCollection

/** The collections offered in the picker, in the order they are shown. */
internal val COLLECTION_KEYS =
    listOf(
        SyncCollection.PLAYLISTS,
        SyncCollection.WATCH_HISTORY,
        SyncCollection.LIKES,
        SyncCollection.SUBSCRIBED_CHANNELS,
        SyncCollection.SUBSCRIPTIONS,
        SyncCollection.SETTINGS,
        SyncCollection.FLOW_NEURO_BRAIN,
        SyncCollection.MUSIC_BRAIN,
        SyncCollection.NOTES,
    )

@Composable
internal fun collectionLabel(key: String): String =
    when (key) {
        SyncCollection.PLAYLISTS -> stringResource(R.string.sync_collection_playlists)
        SyncCollection.WATCH_HISTORY -> stringResource(R.string.sync_collection_watch_history)
        SyncCollection.LIKES -> stringResource(R.string.sync_collection_likes)
        SyncCollection.SUBSCRIBED_CHANNELS -> stringResource(R.string.sync_collection_subscriptions)
        SyncCollection.SUBSCRIPTIONS -> stringResource(R.string.sync_collection_subscription_groups)
        SyncCollection.NOTES -> stringResource(R.string.sync_collection_notes)
        SyncCollection.SETTINGS -> stringResource(R.string.sync_collection_settings)
        SyncCollection.FLOW_NEURO_BRAIN -> stringResource(R.string.sync_collection_recommendation_profile)
        SyncCollection.MUSIC_BRAIN -> stringResource(R.string.sync_collection_music_profile)
        else -> key
    }

/** One line saying what the collection actually covers, shown under its label in the picker. */
@Composable
internal fun collectionDescription(key: String): String? =
    when (key) {
        SyncCollection.PLAYLISTS -> stringResource(R.string.sync_collection_playlists_body)
        SyncCollection.WATCH_HISTORY -> stringResource(R.string.sync_collection_watch_history_body)
        SyncCollection.LIKES -> stringResource(R.string.sync_collection_likes_body)
        SyncCollection.SUBSCRIBED_CHANNELS -> stringResource(R.string.sync_collection_subscriptions_body)
        SyncCollection.SUBSCRIPTIONS -> stringResource(R.string.sync_collection_subscription_groups_body)
        SyncCollection.SETTINGS -> stringResource(R.string.sync_collection_settings_body)
        SyncCollection.FLOW_NEURO_BRAIN -> stringResource(R.string.sync_collection_recommendation_profile_body)
        SyncCollection.MUSIC_BRAIN -> stringResource(R.string.sync_collection_music_profile_body)
        else -> null
    }

internal fun collectionIcon(key: String): ImageVector =
    when (key) {
        SyncCollection.PLAYLISTS -> Icons.AutoMirrored.Outlined.PlaylistPlay
        SyncCollection.WATCH_HISTORY -> Icons.Outlined.History
        SyncCollection.LIKES -> Icons.Outlined.ThumbUp
        SyncCollection.SUBSCRIBED_CHANNELS -> Icons.Outlined.Subscriptions
        SyncCollection.SUBSCRIPTIONS -> Icons.Outlined.Folder
        SyncCollection.SETTINGS -> Icons.Outlined.Tune
        SyncCollection.MUSIC_BRAIN -> Icons.Outlined.LibraryMusic
        SyncCollection.NOTES -> Icons.Outlined.StickyNote2
        else -> Icons.Outlined.Psychology
    }
