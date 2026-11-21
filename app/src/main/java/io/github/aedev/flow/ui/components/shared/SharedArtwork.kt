package io.github.aedev.flow.ui.components.shared

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import coil3.memory.MemoryCache
import io.github.aedev.flow.ui.theme.NavigationMotion

/** The app's shared-transition scope, absent where pages do not share elements (low-RAM phones, previews, TV). */
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** The enter and exit animation of the page being composed, so its artwork can fly to the next page. */
val LocalPageAnimationScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/** Where the images that cards drew are kept, so the page a card opens can show it at once. */
val LocalSharedArtworkImages = staticCompositionLocalOf<SharedArtworkImages?> { null }

/** The image a card drew: its address, and the memory-cache entry holding the decoded bitmap. */
@Immutable
data class SharedArtworkImage(
    val url: String,
    val memoryCacheKey: MemoryCache.Key?,
)

/**
 * The last images the shared-artwork cards drew, by artwork key. A page opened from one shows that
 * image while its own cover loads, so the artwork that flies in is never blank.
 */
class SharedArtworkImages {
    private val images =
        object : LinkedHashMap<String, SharedArtworkImage>(MAX_IMAGES, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SharedArtworkImage>): Boolean = size > MAX_IMAGES
        }

    fun put(
        key: String,
        image: SharedArtworkImage,
    ) {
        images[key] = image
    }

    operator fun get(key: String?): SharedArtworkImage? = key?.let(images::get)
}

/**
 * Lets the pages inside [content] share artwork. It puts them in a lookahead scope, which adds a
 * layout pass, so a low-RAM phone passes [enabled] false and gets the plain page slide instead.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun FlowSharedTransitions(
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        content()
        return
    }
    val images = remember { SharedArtworkImages() }
    SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedTransitionScope provides this, LocalSharedArtworkImages provides images) { content() }
    }
}

/**
 * Artwork that flies from the card that was tapped to the cover of the page it opened. Both ends
 * use the same [key], the id the page was opened with; without a match the page just slides in.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedArtwork(
    key: String?,
    shape: Shape,
): Modifier {
    val transitionScope = LocalSharedTransitionScope.current
    val pageScope = LocalPageAnimationScope.current
    if (key == null || transitionScope == null || pageScope == null) return this
    return with(transitionScope) {
        this@sharedArtwork.sharedBounds(
            sharedContentState = rememberSharedContentState(SharedArtworkKey(key)),
            animatedVisibilityScope = pageScope,
            boundsTransform = { _, _ -> NavigationMotion.ArtworkBounds },
            clipInOverlayDuringTransition = OverlayClip(shape),
        )
    }
}

private data class SharedArtworkKey(
    val id: String,
)

private const val MAX_IMAGES = 64
private const val LOAD_FACTOR = 0.75f
