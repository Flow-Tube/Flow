package io.github.aedev.flow.ui.components.shared.card

import androidx.compose.runtime.Composable
import io.github.aedev.flow.ui.components.shared.CollaboratorsBottomSheet
import io.github.aedev.flow.ui.components.shared.quickactions.VideoQuickActionsBottomSheet

@Composable
internal fun VideoCardSheets(
    state: VideoCardState,
    showChannel: Boolean,
    whyShown: String? = null,
) {
    val sheets = state.sheets
    if (sheets.showQuickActions) {
        VideoQuickActionsBottomSheet(
            video = state.video,
            title = state.title,
            thumbnailUrl = state.thumbnailUrl,
            youTubeFrames = state.youTubeFrames,
            showChannel = showChannel,
            whyShown = whyShown,
            onDismiss = { sheets.showQuickActions = false },
        )
    }

    if (sheets.showCollaborators) {
        CollaboratorsBottomSheet(
            collaborators = state.collaborators,
            onDismiss = { sheets.showCollaborators = false },
        )
    }
}
