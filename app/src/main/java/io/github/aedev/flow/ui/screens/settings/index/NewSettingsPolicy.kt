package io.github.aedev.flow.ui.screens.settings.index

import io.github.aedev.flow.ui.components.settings.SettingEntry
import io.github.aedev.flow.ui.components.settings.SettingsDestination

/**
 * Which settings are new (#1676). Flow notices a key it has not seen before on its own, so nothing has
 * to be tagged per release: the first run marks every key that exists as always known, and any key
 * that appears in a later build is new for a number of days from the day it was first seen.
 */
internal object NewSettingsPolicy {
    /** The day keys that existed before this feature are recorded with; far enough back to never be new. */
    const val ALWAYS_KNOWN = 0L

    val DAY_OPTIONS = listOf(3, 7, 14, 30)

    /** [known] with every key of [keys] it lacks added as seen [today], and keys that left the index dropped. */
    fun record(
        known: Map<String, Long>?,
        keys: Collection<String>,
        today: Long,
    ): Map<String, Long> {
        if (known == null) return keys.associateWith { ALWAYS_KNOWN }
        return keys.associateWith { known[it] ?: today }
    }

    /**
     * The keys to mark: each entry first seen within [days] of [today], and the page rows that lead to
     * it, so the dot can be followed from the settings list down to the option.
     */
    fun newKeys(
        firstSeen: Map<String, Long>?,
        entries: List<SettingEntry>,
        today: Long,
        days: Int,
    ): Set<String> {
        if (firstSeen == null || days <= 0) return emptySet()
        return buildSet {
            entries
                .filter { entry -> firstSeen[entry.key]?.let { today - it < days } == true }
                .forEach { entry ->
                    add(entry.key)
                    entry.destination.path
                        .filter { it != SettingsDestination.HOME }
                        .forEach { add(DestinationIndex.entry(it).key) }
                }
        }
    }
}
