package io.github.aedev.flow.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MusicSkipPolicyTest {
    private val inStep =
        MusicSkipPolicy.Facts(
            targetIndex = 1,
            targetId = "b",
            queueIds = listOf("a", "b", "c"),
            playerIds = listOf("a", "b", "c"),
            playerStopped = false,
            drivesAnotherDevice = false,
            restoredPositionPending = false,
            storageRead = true,
            savedPositionMs = 0L,
            downloadMatchesPlayer = true,
            localInMixedQueue = false,
        )

    private fun route(facts: MusicSkipPolicy.Facts) = MusicSkipPolicy.route(facts)

    @Test
    fun `a skip to an entry the player already holds seeks`() {
        assertThat(route(inStep)).isEqualTo(MusicSkipPolicy.Route.SEEK)
        assertThat(route(inStep.copy(targetIndex = 0, targetId = "a"))).isEqualTo(MusicSkipPolicy.Route.SEEK)
    }

    @Test
    fun `a repeated song in the queue still seeks to its own entry`() {
        val queue = listOf("a", "b", "a")

        assertThat(
            route(inStep.copy(targetIndex = 2, targetId = "a", queueIds = queue, playerIds = queue)),
        ).isEqualTo(MusicSkipPolicy.Route.SEEK)
    }

    @Test
    fun `a queue the player does not hold in the same order rebuilds`() {
        listOf(
            emptyList(),
            listOf("a", "b"),
            listOf("a", "b", "c", "d"),
            listOf("a", "c", "b"),
        ).forEach { player ->
            assertThat(route(inStep.copy(playerIds = player))).isEqualTo(MusicSkipPolicy.Route.QUEUE_OUT_OF_STEP)
        }
        assertThat(route(inStep.copy(targetIndex = 3))).isEqualTo(MusicSkipPolicy.Route.QUEUE_OUT_OF_STEP)
    }

    @Test
    fun `an index left over from another queue never seeks`() {
        assertThat(route(inStep.copy(targetId = "z"))).isEqualTo(MusicSkipPolicy.Route.QUEUE_OUT_OF_STEP)
    }

    @Test
    fun `no player yet goes through the start that waits for the connection`() {
        assertThat(route(inStep.copy(playerIds = null))).isEqualTo(MusicSkipPolicy.Route.NO_PLAYER)
    }

    @Test
    fun `playing on another device never touches the player here`() {
        assertThat(route(inStep.copy(drivesAnotherDevice = true))).isEqualTo(MusicSkipPolicy.Route.DRIVES_ANOTHER_DEVICE)
    }

    @Test
    fun `a stopped or failed player is rebuilt, which also clears its error`() {
        assertThat(route(inStep.copy(playerStopped = true))).isEqualTo(MusicSkipPolicy.Route.PLAYER_STOPPED)
    }

    @Test
    fun `a song with a place to resume from starts through the rebuild that applies it`() {
        assertThat(route(inStep.copy(savedPositionMs = 90_000L))).isEqualTo(MusicSkipPolicy.Route.SAVED_POSITION)
        assertThat(route(inStep.copy(restoredPositionPending = true))).isEqualTo(MusicSkipPolicy.Route.RESTORED_POSITION)
    }

    @Test
    fun `a download the player's item does not point at rebuilds`() {
        assertThat(route(inStep.copy(downloadMatchesPlayer = false))).isEqualTo(MusicSkipPolicy.Route.DOWNLOAD_CHANGED)
    }

    @Test
    fun `without a storage read a skip cannot know it is safe to seek`() {
        assertThat(route(inStep.copy(storageRead = false))).isEqualTo(MusicSkipPolicy.Route.STORAGE_UNREAD)
    }

    @Test
    fun `a device file among streamed songs keeps the rebuild that plays it with its own queue`() {
        assertThat(route(inStep.copy(localInMixedQueue = true))).isEqualTo(MusicSkipPolicy.Route.LOCAL_IN_MIXED_QUEUE)
    }
}
