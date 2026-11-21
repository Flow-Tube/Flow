package io.github.aedev.flow.ui.components.music.section

/** One lazy-list reuse pool per shelf composable on the Music home page. */
internal enum class MusicShelfContentType {
    Chips,
    TrackCards,
    BrainTracks,
    SpeedDial,
    Artists,
    Moods,
    Collections,
    TrackList,
    DailyDiscover,
    QuickPicks,
    Charts,
    Community,
    Discovery,
    Continuation,
    ChipStatus,
}

/**
 * Item keys that stay put when a shelf is added or removed above: each base key as is, and a repeat
 * of one (two shelves YouTube titled alike) numbered by its occurrence so a lazy list never sees a
 * duplicate key.
 */
internal fun uniqueItemKeys(bases: List<String>): List<String> {
    val used = HashSet<String>()
    return bases.map { base ->
        var key = base
        var occurrence = 1
        while (!used.add(key)) key = "$base#${++occurrence}"
        key
    }
}
