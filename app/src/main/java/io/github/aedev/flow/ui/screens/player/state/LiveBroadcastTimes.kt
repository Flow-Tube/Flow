package io.github.aedev.flow.ui.screens.player.state

import io.github.aedev.flow.innertube.models.response.PlayerResponse
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * What a player response says about a stream's broadcast. `liveBroadcastDetails` comes only with a
 * microformat, which web clients return; without it the watch page keeps its relative date.
 */
internal object LiveBroadcastTimes {
    /** When the broadcast really started, in epoch milliseconds. */
    fun startMs(response: PlayerResponse): Long? =
        broadcast(response)
            ?.startTimestamp
            ?.let(::epochMs)
            ?.takeIf { response.videoDetails?.isLiveContent != false }

    /** A stream that has ended: its date reads "Streamed …". An end time settles it when the flags disagree. */
    fun isArchived(response: PlayerResponse): Boolean {
        val details = response.videoDetails
        val broadcast = broadcast(response)
        if (broadcast?.isLiveNow == true) return false
        return (details?.isLiveContent == true && details.isLive != true) || broadcast?.endTimestamp?.let(::epochMs) != null
    }

    private fun broadcast(response: PlayerResponse) = response.microformat?.playerMicroformatRenderer?.liveBroadcastDetails

    private fun epochMs(timestamp: String): Long? =
        try {
            OffsetDateTime.parse(timestamp).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            null
        }
}
