package io.github.aedev.flow.notification

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NewVersionChannelTest {
    @Test
    fun `stable releases use the channel that makes a sound`() {
        assertThat(newVersionChannel("stable", updatesChannelBlocked = false)).isEqualTo(NotificationHelper.CHANNEL_NEW_VERSION)
    }

    @Test
    fun `nightly stays on the silent channel`() {
        assertThat(newVersionChannel("nightly", updatesChannelBlocked = false)).isEqualTo(NotificationHelper.CHANNEL_UPDATES)
    }

    @Test
    fun `a blocked updates channel stays blocked`() {
        assertThat(newVersionChannel("stable", updatesChannelBlocked = true)).isEqualTo(NotificationHelper.CHANNEL_UPDATES)
    }
}
