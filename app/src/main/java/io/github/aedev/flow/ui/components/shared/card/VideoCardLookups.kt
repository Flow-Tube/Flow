package io.github.aedev.flow.ui.components.shared.card

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.aedev.flow.data.contentfilter.aislist.AiListMatcher

/**
 * The upcoming videos the user set a reminder for. Read per id through [rememberHasReminder], so a
 * reminder toggle recomposes only the card it belongs to, as [VideoWatchProgressStore] does for progress.
 */
@Stable
class UpcomingReminders internal constructor(
    private val ids: State<Set<String>>,
) {
    internal fun contains(videoId: String): Boolean = videoId in ids.value

    internal companion object {
        val None = UpcomingReminders(mutableStateOf(emptySet()))
    }
}

val LocalUpcomingReminders = staticCompositionLocalOf { UpcomingReminders.None }

@Composable
internal fun rememberHasReminder(videoId: String): Boolean {
    val reminders = LocalUpcomingReminders.current
    return remember(reminders, videoId) { derivedStateOf { reminders.contains(videoId) } }.value
}

/**
 * The AI channel list's matcher. A new list or setting re-checks each reader through
 * [rememberAiListVerdict] and recomposes only those whose answer changed, instead of the whole tree.
 */
@Stable
class AiListLookup internal constructor(
    private val matcher: State<AiListMatcher>,
) {
    internal val current: AiListMatcher get() = matcher.value

    internal companion object {
        val None = AiListLookup(mutableStateOf(AiListMatcher.NONE))
    }
}

val LocalAiListMatcher = staticCompositionLocalOf { AiListLookup.None }

/** [verdict] over the current matcher, re-run when the matcher or [keys] change. */
@Composable
fun rememberAiListVerdict(
    vararg keys: Any?,
    verdict: (AiListMatcher) -> Boolean,
): Boolean {
    val lookup = LocalAiListMatcher.current
    return remember(lookup, *keys) { derivedStateOf { verdict(lookup.current) } }.value
}
