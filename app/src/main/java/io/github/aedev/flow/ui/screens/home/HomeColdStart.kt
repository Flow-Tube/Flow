package io.github.aedev.flow.ui.screens.home

import androidx.compose.runtime.Immutable
import io.github.aedev.flow.data.model.Video

/** Fewer cached cards than this is not worth showing at launch: the skeleton waits for the new feed. */
internal const val MIN_CACHED_CARDS = 6

/** The new feed is added after the cached cards once the viewer is this close to their end. */
internal const val PENDING_APPEND_LOOKAHEAD = 4

/**
 * The feed wave 1 built while the cached feed was on screen at launch. It never replaces those
 * cards on its own (#1447): the New videos button applies it, or it follows the cached cards.
 */
@Immutable
data class HomePendingFeed(
    val videos: List<Video>,
    val shorts: List<Video>,
    val origins: Map<String, HomeCardOrigin>,
)

/** The cached cards worth showing at launch: those the viewer never had on screen, or none at all. */
internal fun unseenCachedCards(
    cached: List<Video>,
    wasShown: (String) -> Boolean,
): List<Video> = cached.filterNot { wasShown(it.id) }.takeIf { it.size >= MIN_CACHED_CARDS }.orEmpty()

/**
 * The feed after the New videos button: the new cards first, then what late merges added below the
 * cached ones. The cached cards themselves go.
 */
internal fun feedWithPendingApplied(
    onScreen: List<Video>,
    cachedIds: Set<String>,
    pending: List<Video>,
): List<Video> = (pending + onScreen.filterNot { it.id in cachedIds }).distinctBy { it.id }

/** The feed with the new cards after everything on screen, which stays where it is. */
internal fun feedWithPendingAppended(
    onScreen: List<Video>,
    pending: List<Video>,
): List<Video> = (onScreen + pending).distinctBy { it.id }

internal fun nearsEndOfFeed(
    lastVisibleIndex: Int,
    itemCount: Int,
): Boolean = itemCount > 0 && lastVisibleIndex >= itemCount - PENDING_APPEND_LOOKAHEAD
