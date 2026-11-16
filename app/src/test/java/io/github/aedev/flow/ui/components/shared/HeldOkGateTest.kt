package io.github.aedev.flow.ui.components.shared

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** OK still held from the long press that opened a menu or dialog must not select anything in it. */
class HeldOkGateTest {
    @Test
    fun repeatsAndTheReleaseOfAHeldOkAreSwallowed() {
        val gate = HeldOkGate()
        assertThat(gate.swallows(isKeyDown = true, repeatCount = 12)).isTrue()
        assertThat(gate.swallows(isKeyDown = true, repeatCount = 13)).isTrue()
        assertThat(gate.swallows(isKeyDown = false, repeatCount = 0)).isTrue()
    }

    @Test
    fun aReleaseWithNoPressHereIsSwallowed() {
        assertThat(HeldOkGate().swallows(isKeyDown = false, repeatCount = 0)).isTrue()
    }

    @Test
    fun aFreshPressAfterTheHeldOneClicksAsUsual() {
        val gate = HeldOkGate()
        gate.swallows(isKeyDown = true, repeatCount = 4)
        gate.swallows(isKeyDown = false, repeatCount = 0)
        assertThat(gate.swallows(isKeyDown = true, repeatCount = 0)).isFalse()
        assertThat(gate.swallows(isKeyDown = false, repeatCount = 0)).isFalse()
    }

    @Test
    fun holdingAFreshPressStillPassesItsRepeats() {
        val gate = HeldOkGate()
        gate.swallows(isKeyDown = true, repeatCount = 0)
        assertThat(gate.swallows(isKeyDown = true, repeatCount = 1)).isFalse()
    }
}
