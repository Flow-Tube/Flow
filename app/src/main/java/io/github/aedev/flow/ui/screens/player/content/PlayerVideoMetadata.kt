package io.github.aedev.flow.ui.screens.player.content

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.model.VideoCollaborator
import io.github.aedev.flow.data.model.needsCollaboratorResolution
import io.github.aedev.flow.data.model.toVideo
import io.github.aedev.flow.data.repository.VideoCollaboratorResolver
import io.github.aedev.flow.ui.components.rememberDeArrowResult
import io.github.aedev.flow.ui.components.shared.card.LocalVideoCardPreferences
import io.github.aedev.flow.ui.components.shared.card.rememberKnownOriginalTitle
import io.github.aedev.flow.ui.components.shared.rememberDateDisplaySettings
import io.github.aedev.flow.ui.screens.player.state.VideoPlayerUiState
import io.github.aedev.flow.utils.DateContext
import org.schabi.newpipe.extractor.stream.StreamType

/**
 * Display values derived from the played [Video] and the loaded stream info.
 */
@Immutable
internal data class PlayerVideoMetadata(
    val resolvedVideoTitle: String,
    val resolvedCollaborators: List<VideoCollaborator>,
    val resolvedChannelName: String,
    val streamUploadDate: String?,
    val dialogVideo: Video,
)

@Composable
internal fun rememberPlayerVideoMetadata(
    video: Video,
    uiState: VideoPlayerUiState,
    deArrowEnabled: Boolean,
    context: Context,
): PlayerVideoMetadata {
    val deArrowResult = rememberDeArrowResult(video, deArrowEnabled, useRandomFrame = false)
    val cachedOriginal = rememberKnownOriginalTitle(video.id, LocalVideoCardPreferences.current.originalTitlesEnabled)
    val resolvedVideoTitle = deArrowResult?.title ?: cachedOriginal ?: video.title
    val needsCollaboratorResolution = video.needsCollaboratorResolution()
    val resolvedCollaborators by produceState(
        initialValue = video.collaborators,
        key1 = video.id,
        key2 = video.collaborators,
        key3 = needsCollaboratorResolution,
    ) {
        value =
            if (needsCollaboratorResolution) {
                VideoCollaboratorResolver.resolve(video.id)
            } else {
                video.collaborators
            }
    }
    val resolvedChannelName =
        remember(video.channelName, resolvedCollaborators) {
            resolvedCollaborators
                .map { it.name }
                .filter { it.isNotBlank() }
                .takeIf { it.size > 1 }
                ?.joinToString(" ${context.getString(R.string.conjunction_and)} ")
                ?: video.channelName
        }
    val dateSettings = rememberDateDisplaySettings()
    val streamUploadDate =
        remember(video.uploadDate, uiState.isArchivedLivestream, dateSettings) {
            val rawDate = video.uploadDate.takeIf { it.isNotBlank() } ?: return@remember null
            if (uiState.isArchivedLivestream && !rawDate.startsWith("Streamed", ignoreCase = true)) {
                context.getString(R.string.streamed_date_template, rawDate)
            } else {
                rawDate
            }
        }
    val dialogVideo = video

    return PlayerVideoMetadata(
        resolvedVideoTitle = resolvedVideoTitle,
        resolvedCollaborators = resolvedCollaborators,
        resolvedChannelName = resolvedChannelName,
        streamUploadDate = streamUploadDate,
        dialogVideo = dialogVideo,
    )
}
