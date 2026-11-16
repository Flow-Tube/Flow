package io.github.aedev.flow.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.data.model.DeArrowContent
import io.github.aedev.flow.data.model.DeArrowResult
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.repository.DeArrowBrandingPolicy
import io.github.aedev.flow.data.repository.DeArrowRepository

/** The activity's DeArrow lookup, installed by `ProvideVideoCardState`; null in previews and tests. */
val LocalDeArrow = staticCompositionLocalOf<DeArrowRepository?> { null }

/**
 * DeArrow's [result] for a video, and whether the lookup has [answered]: true once the server replied
 * or failed, and at once when there is nothing to ask.
 */
@Immutable
internal data class DeArrowLookup(
    val result: DeArrowResult?,
    val answered: Boolean,
)

/**
 * What DeArrow replaces on [video], or null to keep YouTube's: switched off, a local file, no lookup
 * installed, or nothing to replace. An answer already fetched shows on the first frame.
 */
@Composable
internal fun rememberDeArrowResult(
    video: Video,
    enabled: Boolean,
    useRandomFrame: Boolean,
): DeArrowResult? = rememberDeArrowLookup(video, enabled, useRandomFrame).result

/** [rememberDeArrowResult], also saying whether the answer is in yet. */
@Composable
internal fun rememberDeArrowLookup(
    video: Video,
    enabled: Boolean,
    useRandomFrame: Boolean,
): DeArrowLookup {
    val repository = LocalDeArrow.current
    val active = enabled && repository != null && !LocalMediaIds.isLocal(video.id)
    val resolve = { content: DeArrowContent? ->
        content?.let {
            DeArrowBrandingPolicy.resolve(
                content = it,
                videoId = video.id,
                fallbackDurationSeconds = video.duration,
                useRandomFrame = useRandomFrame,
                isLiveOrUpcoming = video.isLive || video.isUpcoming,
            )
        }
    }
    return key(video.id, active, useRandomFrame) {
        val initial =
            when {
                !active || repository == null -> DeArrowLookup(null, answered = true)
                repository.isCached(video.id) -> DeArrowLookup(resolve(repository.cached(video.id)), answered = true)
                else -> DeArrowLookup(null, answered = false)
            }
        produceState(initialValue = initial, video.id, active, useRandomFrame) {
            if (active && !initial.answered) value = DeArrowLookup(resolve(repository?.branding(video.id)), answered = true)
        }.value
    }
}
