package io.github.aedev.flow.applock

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppLockPolicyTest {
    private val minute = 60_000L

    @Test
    fun `coming back without having left never locks`() {
        assertThat(AwayClock().returned(nowMs = 10 * minute, delayMinutes = 0)).isFalse()
    }

    @Test
    fun `immediately locks on any return`() {
        val clock = AwayClock()
        clock.left(1_000L)
        assertThat(clock.returned(1_000L, delayMinutes = 0)).isTrue()
    }

    @Test
    fun `a return before the delay doesn't lock and ends the time away`() {
        val clock = AwayClock()
        clock.left(0L)
        assertThat(clock.returned(5 * minute - 1, delayMinutes = 5)).isFalse()
        assertThat(clock.returned(20 * minute, delayMinutes = 5)).isFalse()
    }

    @Test
    fun `a return at the delay locks`() {
        val clock = AwayClock()
        clock.left(0L)
        assertThat(clock.returned(minute, delayMinutes = 1)).isTrue()
    }

    @Test
    fun `the first leave counts, so closing PiP later doesn't restart the wait`() {
        val clock = AwayClock()
        clock.left(0L)
        clock.left(4 * minute)
        assertThat(clock.returned(5 * minute, delayMinutes = 5)).isTrue()
    }

    @Test
    fun `an unlock forgets the time away`() {
        val clock = AwayClock()
        clock.left(0L)
        clock.reset()
        assertThat(clock.returned(15 * minute, delayMinutes = 1)).isFalse()
    }

    @Test
    fun `Android 9 and 10 use weak biometrics, every other version strong`() {
        assertThat(usesStrongBiometrics(26)).isTrue()
        assertThat(usesStrongBiometrics(27)).isTrue()
        assertThat(usesStrongBiometrics(28)).isFalse()
        assertThat(usesStrongBiometrics(29)).isFalse()
        assertThat(usesStrongBiometrics(30)).isTrue()
        assertThat(usesStrongBiometrics(36)).isTrue()
    }

    @Test
    fun `the delays offered match the default`() {
        assertThat(APP_LOCK_DELAYS_MINUTES).containsExactly(0, 1, 5, 15).inOrder()
        assertThat(APP_LOCK_DELAYS_MINUTES).contains(DEFAULT_APP_LOCK_DELAY_MINUTES)
    }
}
