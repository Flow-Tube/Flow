package io.github.aedev.flow.ui.screens.music

/** The two network fetches a pull to refresh waits for before its indicator goes. */
internal enum class MusicRefreshLeg { HOME, CHARTS }

/** What a pull to refresh has heard back so far. */
internal data class MusicHomeRefresh(
    private val finished: Set<MusicRefreshLeg> = emptySet(),
    private val succeeded: Set<MusicRefreshLeg> = emptySet(),
) {
    val isSettled: Boolean get() = finished.size == MusicRefreshLeg.entries.size

    /** Settled with nothing new from either fetch, so the shelves on screen are the old ones. */
    val isFailed: Boolean get() = isSettled && succeeded.isEmpty()

    fun settle(
        leg: MusicRefreshLeg,
        ok: Boolean,
    ): MusicHomeRefresh =
        MusicHomeRefresh(
            finished = finished + leg,
            succeeded = if (ok) succeeded + leg else succeeded,
        )
}
