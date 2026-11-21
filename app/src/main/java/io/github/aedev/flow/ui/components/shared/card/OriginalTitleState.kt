package io.github.aedev.flow.ui.components.shared.card

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.repository.OriginalTitleRepository

/** The activity's title lookup, installed by [ProvideVideoCardState]; null in previews and tests. */
val LocalOriginalTitles = staticCompositionLocalOf<OriginalTitleRepository?> { null }

/**
 * The title in the video's own language, or null to keep the shown one: switched off, a local file,
 * no lookup installed, or none known yet. A title already fetched shows on the first frame, and only
 * a missing answer starts a coroutine.
 */
@Composable
fun rememberOriginalTitle(
    videoId: String,
    enabled: Boolean,
): String? {
    val titles = LocalOriginalTitles.current
    val active = enabled && titles != null && !LocalMediaIds.isLocal(videoId)
    return key(videoId, active) {
        when {
            !active || titles == null -> null
            titles.isAnswered(videoId) -> titles.cached(videoId)
            else -> produceState<String?>(initialValue = null, videoId) { value = titles.originalTitle(videoId) }.value
        }
    }
}

/**
 * A title some card already looked up, picked up when that lookup lands. Never requests one: a
 * page loaded with the switch on already carries the original, so this only covers flipping the
 * switch while a page is open.
 */
@Composable
fun rememberKnownOriginalTitle(
    videoId: String,
    enabled: Boolean,
): String? {
    val titles = LocalOriginalTitles.current
    val active = enabled && titles != null && !LocalMediaIds.isLocal(videoId)
    return key(videoId, active) {
        produceState(initialValue = if (active) titles?.cached(videoId) else null, videoId, active) {
            if (active) titles?.cachedUpdates(videoId)?.collect { value = it }
        }.value
    }
}

/** The title a card without DeArrow shows: the original when the switch asks for it. */
@Composable
fun rememberCardTitle(video: Video): String =
    rememberOriginalTitle(video.id, LocalVideoCardPreferences.current.originalTitlesEnabled) ?: video.title
