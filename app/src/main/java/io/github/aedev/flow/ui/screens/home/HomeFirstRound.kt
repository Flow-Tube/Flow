package io.github.aedev.flow.ui.screens.home

/**
 * How much of Home's first round runs at once. Every search page and watch response is held twice
 * while it parses (the text and its tree), so a small heap fetches fewer of each and runs the
 * related lane after the searches rather than beside them (#2204).
 */
internal data class HomeFirstRound(
    val queryCount: Int,
    val relatedSeeds: Int,
    val lanesTogether: Boolean,
) {
    companion object {
        fun forHeap(isSmallHeap: Boolean): HomeFirstRound =
            if (isSmallHeap) {
                HomeFirstRound(queryCount = 2, relatedSeeds = 2, lanesTogether = false)
            } else {
                HomeFirstRound(queryCount = 3, relatedSeeds = 4, lanesTogether = true)
            }
    }
}
