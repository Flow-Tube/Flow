package io.github.aedev.flow.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PipActionsTest {
    @Test
    fun `a queue puts the skip buttons either side of pause`() {
        val specs = pipActionSpecs(isPlaying = true, navigation = PipQueueNavigation(hasPrevious = true, hasNext = true), maxActions = 3)

        assertEquals(listOf(PipControl.PREVIOUS, PipControl.PAUSE, PipControl.NEXT), specs.map { it.control })
        assertEquals(listOf(true, true, true), specs.map { it.enabled })
    }

    @Test
    fun `the first reel greys out previous and the last greys out next`() {
        val first = pipActionSpecs(isPlaying = false, navigation = PipQueueNavigation(hasPrevious = false, hasNext = true), maxActions = 3)
        val last = pipActionSpecs(isPlaying = true, navigation = PipQueueNavigation(hasPrevious = true, hasNext = false), maxActions = 3)

        assertEquals(PipControl.PLAY, first[1].control)
        assertEquals(listOf(false, true, true), first.map { it.enabled })
        assertEquals(listOf(true, true, false), last.map { it.enabled })
    }

    @Test
    fun `without a queue the window keeps its single play or pause button`() {
        assertEquals(listOf(PipActionSpec(PipControl.PAUSE)), pipActionSpecs(isPlaying = true, navigation = null, maxActions = 3))
        assertEquals(listOf(PipActionSpec(PipControl.PLAY)), pipActionSpecs(isPlaying = false, navigation = null, maxActions = 3))
    }

    @Test
    fun `a window with room for fewer than three buttons drops the skip buttons`() {
        val specs = pipActionSpecs(isPlaying = true, navigation = PipQueueNavigation(hasPrevious = true, hasNext = true), maxActions = 2)

        assertEquals(listOf(PipActionSpec(PipControl.PAUSE)), specs)
    }

    @Test
    fun `a video window has listen in the background, play or pause, then next`() {
        val specs = pipActionSpecs(isPlaying = true, navigation = null, maxActions = 3, offerBackground = true, videoHasNext = true)

        assertEquals(
            listOf(
                PipActionSpec(PipControl.BACKGROUND),
                PipActionSpec(PipControl.PAUSE),
                PipActionSpec(PipControl.NEXT),
            ),
            specs,
        )
    }

    @Test
    fun `a video with nothing to play next greys the button and keeps the layout`() {
        val specs = pipActionSpecs(isPlaying = false, navigation = null, maxActions = 3, offerBackground = true, videoHasNext = false)

        assertEquals(listOf(PipControl.BACKGROUND, PipControl.PLAY, PipControl.NEXT), specs.map { it.control })
        assertEquals(listOf(true, true, false), specs.map { it.enabled })
    }

    @Test
    fun `a video window with room for two buttons drops next first`() {
        val specs = pipActionSpecs(isPlaying = true, navigation = null, maxActions = 2, offerBackground = true, videoHasNext = true)

        assertEquals(listOf(PipControl.BACKGROUND, PipControl.PAUSE), specs.map { it.control })
    }

    @Test
    fun `next goes to the video unless Shorts owns the window`() {
        assertEquals(true, videoHandlesPipNext(shortsNavigation = null))
        assertEquals(false, videoHandlesPipNext(PipQueueNavigation(hasPrevious = true, hasNext = true)))
    }

    @Test
    fun `a window with room for one button keeps only play or pause`() {
        val specs = pipActionSpecs(isPlaying = false, navigation = null, maxActions = 1, offerBackground = true)

        assertEquals(listOf(PipActionSpec(PipControl.PLAY)), specs)
    }

    @Test
    fun `a queue never shows the background button`() {
        val specs =
            pipActionSpecs(
                isPlaying = true,
                navigation = PipQueueNavigation(hasPrevious = true, hasNext = true),
                maxActions = 3,
                offerBackground = true,
                videoHasNext = false,
            )

        assertEquals(listOf(PipControl.PREVIOUS, PipControl.PAUSE, PipControl.NEXT), specs.map { it.control })
        assertEquals(listOf(true, true, true), specs.map { it.enabled })
    }
}
