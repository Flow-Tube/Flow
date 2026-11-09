package io.github.aedev.flow.ui.screens.recap.story

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import io.github.aedev.flow.data.stats.RecapSummary
import io.github.aedev.flow.ui.components.shared.rememberMediaArtworkTint

/** The colours one story page is painted in. */
@Immutable
internal data class StoryTint(
    val container: Color,
    val onContainer: Color,
    val accent: Color,
    val raised: Color,
)

/**
 * A tint for every page: pages about a channel, a video or an artist take their colour from its
 * image, the rest cycle through the theme's container roles. Images load through Coil's cache.
 */
@Composable
internal fun rememberStoryTints(
    pages: List<StoryPage>,
    summary: RecapSummary,
): List<StoryTint> {
    val scheme = MaterialTheme.colorScheme
    val roles =
        listOf(
            StoryTint(scheme.primaryContainer, scheme.onPrimaryContainer, scheme.primary, scheme.surfaceContainerHighest),
            StoryTint(scheme.tertiaryContainer, scheme.onTertiaryContainer, scheme.tertiary, scheme.surfaceContainerHighest),
            StoryTint(scheme.secondaryContainer, scheme.onSecondaryContainer, scheme.secondary, scheme.surfaceContainerHighest),
            StoryTint(scheme.surfaceContainerHigh, scheme.onSurface, scheme.primary, scheme.surfaceContainerHighest),
        )
    val channel =
        artworkTint(
            summary.video.topChannels
                .firstOrNull()
                ?.imageUrl,
        )
    val video =
        artworkTint(
            summary.video.topVideos
                .firstOrNull()
                ?.imageUrl,
        )
    val artist =
        artworkTint(
            summary.music.topArtists
                .firstOrNull()
                ?.imageUrl,
        )
    return pages.mapIndexed { index, page ->
        when (page) {
            is StoryPage.TopChannel -> channel
            is StoryPage.OnRepeat -> video
            is StoryPage.TopArtist, StoryPage.Songs -> artist
            StoryPage.Summary -> if (summary.music.topArtists.isNotEmpty() && summary.video.topChannels.isEmpty()) artist else channel
            else -> null
        } ?: roles[index % roles.size]
    }
}

@Composable
private fun artworkTint(url: String?): StoryTint? {
    val tint = rememberMediaArtworkTint(url?.takeIf { it.isNotBlank() }, followsSetting = false)
    if (url.isNullOrBlank()) return null
    return StoryTint(tint.container, tint.onContainer, tint.accent, tint.raised)
}
