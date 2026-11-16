package io.github.aedev.flow.service

import io.github.aedev.flow.data.localmedia.LocalMediaIds

/**
 * Whether the next song's lyrics are worth fetching before it starts. Skipped songs cost one lookup
 * each, so this needs a network that isn't being saved, and a phone that is the one playing.
 */
internal fun shouldFetchLyricsAhead(
    currentId: String,
    nextId: String?,
    nextHasNames: Boolean,
    online: Boolean,
    dataSaving: Boolean,
    playingElsewhere: Boolean,
): Boolean =
    !nextId.isNullOrBlank() &&
        nextId != currentId &&
        !LocalMediaIds.isLocal(nextId) &&
        nextHasNames &&
        online &&
        !dataSaving &&
        !playingElsewhere
