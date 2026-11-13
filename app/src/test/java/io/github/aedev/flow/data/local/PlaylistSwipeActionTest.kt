package io.github.aedev.flow.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistSwipeActionTest {
    @Test
    fun `reads a stored choice`() {
        assertEquals(PlaylistSwipeAction.MOVE_TO_TOP, PlaylistSwipeAction.fromString("MOVE_TO_TOP"))
        assertEquals(PlaylistSwipeAction.REMOVE, PlaylistSwipeAction.fromString("REMOVE"))
    }

    @Test
    fun `nothing stored or an unknown value means off`() {
        assertEquals(PlaylistSwipeAction.NONE, PlaylistSwipeAction.fromString(null))
        assertEquals(PlaylistSwipeAction.NONE, PlaylistSwipeAction.fromString("PLAY_NEXT"))
    }

    @Test
    fun `both sides start off`() {
        assertEquals(PlaylistSwipes(PlaylistSwipeAction.NONE, PlaylistSwipeAction.NONE), PlaylistSwipes())
    }
}
