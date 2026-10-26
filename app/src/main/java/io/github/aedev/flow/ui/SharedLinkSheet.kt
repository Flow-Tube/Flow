package io.github.aedev.flow.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.NavController
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.SharedLinkAction
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.player.GlobalPlayerState
import io.github.aedev.flow.ui.components.shared.quickactions.QuickActionsViewModel
import io.github.aedev.flow.ui.components.shared.quickactions.VideoQuickActionsBottomSheet
import io.github.aedev.flow.ui.components.shared.quickactions.sharedQuickActionsViewModel
import io.github.aedev.flow.utils.ThumbnailUrlResolver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Whether a video shared to Flow opens its menu instead of playing; links tapped elsewhere never ask. */
internal fun sharedLinkAsks(
    action: SharedLinkAction,
    mediaLoaded: Boolean,
): Boolean =
    when (action) {
        SharedLinkAction.PLAY -> false
        SharedLinkAction.ASK_WHILE_PLAYING -> mediaLoaded
        SharedLinkAction.ALWAYS_ASK -> true
    }

/** A video or a song is loaded, so playing a shared video would replace it and its queue. */
internal fun mediaLoaded(): Boolean = GlobalPlayerState.currentVideo.value != null || EnhancedMusicPlayerManager.currentTrack.value != null

/**
 * Plays a video or short handed in from outside, except that a video shared to Flow may first open
 * [SharedLinkSheet], as the Shared links setting says. The sheet survives a recreation; Play now waits
 * for the navigation graph, which a cold start may not have yet.
 */
@Composable
internal fun HandleIncomingVideo(
    videoId: String?,
    isShort: Boolean,
    isShared: Boolean,
    navController: NavController,
    preferences: PlayerPreferences,
    onConsumed: () -> Unit,
) {
    var sharedVideoId by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    HandleDeepLinks(
        deeplinkVideoId = videoId,
        isShort = isShort,
        navController = navController,
        onDeeplinkConsumed = onConsumed,
        asksFirst = { isShared && sharedLinkAsks(preferences.sharedLinkAction.first(), mediaLoaded()) },
        onAsk = { sharedVideoId = it },
    )
    sharedVideoId?.let { id ->
        SharedLinkSheet(
            videoId = id,
            onPlayNow = {
                scope.launch {
                    navController.awaitGraph()
                    navController.navigate("player/$id") { launchSingleTop = true }
                }
            },
            onDismiss = { sharedVideoId = null },
        )
    }
}

/**
 * The menu a shared video opens while something plays (#1116): the video's own menu with Play now on
 * top, so Play next and Add to queue keep the queue. It opens at once on the id's thumbnail and fills
 * in the title and channel when the watch response arrives.
 */
@Composable
internal fun SharedLinkSheet(
    videoId: String,
    onPlayNow: () -> Unit,
    onDismiss: () -> Unit,
    viewModel: QuickActionsViewModel = sharedQuickActionsViewModel(),
) {
    var video by remember(videoId) { mutableStateOf(linkedVideo(videoId)) }
    LaunchedEffect(videoId) {
        viewModel.loadLinkedVideo(video)?.let { video = it }
    }
    VideoQuickActionsBottomSheet(
        video = video,
        onDismiss = onDismiss,
        thumbnailUrl = ThumbnailUrlResolver.normalizeVideoThumbnail(videoId, video.thumbnailUrl),
        onPlayNow = onPlayNow,
        viewModel = viewModel,
    )
}

/** Opens the download menu for a video shared to Download with Flow, as the menu's Download does. */
@Composable
internal fun HandleIncomingDownload(
    videoId: String?,
    onConsumed: () -> Unit,
    viewModel: QuickActionsViewModel = sharedQuickActionsViewModel(),
) {
    LaunchedEffect(videoId) {
        if (videoId == null) return@LaunchedEffect
        onConsumed()
        viewModel.requestLinkedDownload(linkedVideo(videoId))
    }
}
