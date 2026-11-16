package io.github.aedev.flow.ui.screens.playon

/** From this many offline devices on, they fold into one "Not available" row. */
internal const val FOLD_OFFLINE_AT = 2

/**
 * The sheet's device rows: [reachable] ones first, this device on top and the rest by name, so a
 * row never moves when playback does; then [offline] ones, the most recently seen first.
 */
internal data class PlayOnRows(
    val reachable: List<PlayOnDevice>,
    val offline: List<PlayOnDevice>,
) {
    val foldOffline: Boolean get() = offline.size >= FOLD_OFFLINE_AT
}

/** Sorts [devices] into rows. The [active] device counts as reachable even when the roster says otherwise. */
internal fun playOnRows(
    devices: List<PlayOnDevice>,
    active: String?,
): PlayOnRows {
    val (offline, reachable) = devices.partition { it.group == PlayOnGroup.OFFLINE && it.did != active }
    return PlayOnRows(
        reachable = reachable.sortedWith(compareByDescending<PlayOnDevice> { it.thisDevice }.thenBy { it.name.lowercase() }),
        offline = offline.sortedByDescending { it.seenAt ?: 0L },
    )
}
