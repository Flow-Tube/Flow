package io.github.aedev.flow.service

import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ListenRecoveryTest {
    private fun continues(
        reason: Int = Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,
        next: String? = "song",
        current: String? = "song",
        recovering: String? = "song",
    ) = ListenRecovery.continuesThroughTransition(reason, next, current, recovering)

    @Test
    fun `a stop ends the listen and a failure does not`() {
        assertThat(ListenRecovery.endsOnIdle(failed = false)).isTrue()
        assertThat(ListenRecovery.endsOnIdle(failed = true)).isFalse()
    }

    @Test
    fun `a fresh stream swapped in for the failing song keeps the listen`() {
        assertThat(continues()).isTrue()
    }

    @Test
    fun `the same song started again without a failure is a new listen`() {
        assertThat(continues(recovering = null)).isFalse()
    }

    @Test
    fun `recovery moving on to another song ends the listen`() {
        assertThat(continues(next = "other")).isFalse()
        assertThat(continues(next = "other", recovering = "other")).isFalse()
    }

    @Test
    fun `an auto advance or a seek into the same song is a new listen`() {
        assertThat(continues(reason = Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)).isFalse()
        assertThat(continues(reason = Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)).isFalse()
        assertThat(continues(reason = Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)).isFalse()
    }

    @Test
    fun `no item ends the listen`() {
        assertThat(continues(next = null, current = null, recovering = null)).isFalse()
    }
}
