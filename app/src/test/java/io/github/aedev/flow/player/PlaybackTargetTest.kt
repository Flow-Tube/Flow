package io.github.aedev.flow.player

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test

/** While the user drives another device, the music player's actions go there, and that device's own requests stay here. */
class PlaybackTargetTest {
    private val sent = mutableListOf<String>()
    private val remote =
        object : RemotePlayer {
            override val playing = true
            override val queue = listOf("a", "b")
            override val index = 0

            override fun command(
                cmd: String,
                pos: Long?,
            ) {
                sent += listOfNotNull(cmd, pos).joinToString(" ")
            }

            override fun play(
                queue: List<String>,
                index: Int,
                music: Boolean,
            ) {
                sent += "play ${queue[index]}"
            }

            override fun setQueue(ids: List<String>) {
                sent += "queue $ids"
            }

            override fun release() {
                sent += "release"
            }
        }

    @After
    fun tearDown() {
        PlaybackTarget.remote = null
    }

    @Test
    fun `the music player's actions go to the driven device`() {
        PlaybackTarget.remote = remote

        EnhancedMusicPlayerManager.togglePlayPause()
        EnhancedMusicPlayerManager.playNext()
        EnhancedMusicPlayerManager.playPrevious()
        EnhancedMusicPlayerManager.seekTo(5_000)
        EnhancedMusicPlayerManager.play()

        assertThat(sent).containsExactly("pause", "next", "previous", "seek 5000", "play").inOrder()
    }

    @Test
    fun `a request from another device runs here`() {
        PlaybackTarget.remote = remote

        PlaybackTarget.here { EnhancedMusicPlayerManager.playNext() }

        assertThat(sent).isEmpty()
        assertThat(PlaybackTarget.current).isSameInstanceAs(remote)
    }

    @Test
    fun `play next and add to queue edit the driven device's queue`() {
        remote.playNext("c")
        remote.addToQueue("d")

        assertThat(sent).containsExactly("queue [a, c, b]", "queue [a, b, d]").inOrder()
    }
}
