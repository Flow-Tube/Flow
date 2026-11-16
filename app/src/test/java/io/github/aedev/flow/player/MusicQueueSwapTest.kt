package io.github.aedev.flow.player

import android.app.Application
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** A linked device's `cmd queue` replaces the music queue around the song playing, which goes on uninterrupted. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class MusicQueueSwapTest {
    private fun item(id: String) =
        MediaItem
            .Builder()
            .setMediaId(id)
            .setUri("https://example.invalid/$id")
            .build()

    private fun Player.ids() = (0 until mediaItemCount).map { getMediaItemAt(it).mediaId }

    @Test
    fun the_new_queue_goes_around_the_song_playing() {
        assertEquals(listOf("x") to listOf("y", "z"), aroundCurrent(listOf("x", "c", "y", "z")) { it == "c" })
        assertEquals(
            "a song not in the new queue stays, and the queue follows it",
            emptyList<String>() to listOf("x", "y"),
            aroundCurrent(listOf("x", "y")) {
                it ==
                    "c"
            },
        )
    }

    @Test
    fun the_player_keeps_the_current_song_while_the_rest_changes() {
        val player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext()).build()
        try {
            player.setMediaItems(listOf("a", "b", "c", "d").map(::item), 2, 0)
            var transitions = 0
            player.addListener(
                object : Player.Listener {
                    override fun onMediaItemTransition(
                        mediaItem: MediaItem?,
                        reason: Int,
                    ) {
                        transitions++
                    }
                },
            )

            player.replaceAround(listOf(item("x")), listOf(item("y"), item("z")))
            ShadowLooper.idleMainLooper()

            assertEquals(listOf("x", "c", "y", "z"), player.ids())
            assertEquals(1, player.currentMediaItemIndex)
            assertEquals("c", player.currentMediaItem?.mediaId)
            assertEquals("playback never moved to another song", 0, transitions)
        } finally {
            player.release()
        }
    }
}
