package io.github.aedev.flow.ui.components.videoplayer.sheet

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class QueueSheetScrollTest {
    @Test
    fun `the target keeps two videos above the current one`() {
        assertThat(queueScrollTarget(10)).isEqualTo(8)
        assertThat(queueScrollTarget(2)).isEqualTo(0)
    }

    @Test
    fun `the target never goes above the first row`() {
        assertThat(queueScrollTarget(1)).isEqualTo(0)
        assertThat(queueScrollTarget(0)).isEqualTo(0)
    }

    @Test
    fun `a target within seven rows animates the whole way`() {
        assertThat(queueScrollJump(target = 9, firstVisibleIndex = 8)).isNull()
        assertThat(queueScrollJump(target = 14, firstVisibleIndex = 8)).isNull()
        assertThat(queueScrollJump(target = 2, firstVisibleIndex = 8)).isNull()
        assertThat(queueScrollJump(target = 8, firstVisibleIndex = 8)).isNull()
    }

    @Test
    fun `a target seven or more rows below jumps to seven rows short of it`() {
        assertThat(queueScrollJump(target = 15, firstVisibleIndex = 8)).isEqualTo(8)
        assertThat(queueScrollJump(target = 100, firstVisibleIndex = 0)).isEqualTo(93)
    }

    @Test
    fun `a target seven or more rows above jumps to seven rows past it`() {
        assertThat(queueScrollJump(target = 1, firstVisibleIndex = 8)).isEqualTo(8)
        assertThat(queueScrollJump(target = 0, firstVisibleIndex = 50)).isEqualTo(7)
    }
}
