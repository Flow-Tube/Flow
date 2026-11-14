package io.github.aedev.flow.service

import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TaskRemovalPolicyTest {
    private fun keeps(
        ongoing: Boolean = true,
        playWhenReady: Boolean = true,
        state: Int = Player.STATE_READY,
        hasMedia: Boolean = true,
        keepPlayingAfterSwipe: Boolean = true,
    ) = keepsPlayingOnTaskRemoved(keepPlayingAfterSwipe, ongoing, playWhenReady, state, hasMedia)

    @Test
    fun `playing media keeps the service`() {
        assertThat(keeps()).isTrue()
    }

    @Test
    fun `a song still loading keeps the service`() {
        assertThat(keeps(state = Player.STATE_BUFFERING)).isTrue()
    }

    @Test
    fun `a pause stops the service even while the notification is still in the foreground`() {
        assertThat(keeps(playWhenReady = false)).isFalse()
        assertThat(keeps(playWhenReady = false, state = Player.STATE_BUFFERING)).isFalse()
    }

    @Test
    fun `a stopped, finished or empty player stops the service`() {
        assertThat(keeps(state = Player.STATE_IDLE)).isFalse()
        assertThat(keeps(state = Player.STATE_ENDED)).isFalse()
        assertThat(keeps(hasMedia = false)).isFalse()
    }

    @Test
    fun `with the switch off a swipe stops even playing media`() {
        assertThat(keeps(keepPlayingAfterSwipe = false)).isFalse()
        assertThat(keeps(keepPlayingAfterSwipe = false, state = Player.STATE_BUFFERING)).isFalse()
    }

    @Test
    fun `a service that is not in the foreground always stops`() {
        assertThat(keeps(ongoing = false)).isFalse()
    }

    @Test
    fun `the lock screen player and widget setup ending are not the app being swiped away`() {
        assertThat(isSideTaskRemoval("io.github.aedev.flow.ui.screens.lockscreen.LockScreenPlayerActivity")).isTrue()
        assertThat(isSideTaskRemoval("io.github.aedev.flow.widget.config.WidgetConfigActivity")).isTrue()
    }

    @Test
    fun `the app's own task, its launcher aliases and an unknown root still count as a swipe`() {
        assertThat(isSideTaskRemoval("io.github.aedev.flow.MainActivity")).isFalse()
        assertThat(isSideTaskRemoval("io.github.aedev.flow.IconAmoled")).isFalse()
        assertThat(isSideTaskRemoval("io.github.aedev.flow.TvActivity")).isFalse()
        assertThat(isSideTaskRemoval(null)).isFalse()
    }
}
