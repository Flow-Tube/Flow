package io.github.aedev.flow.ui.screens.player.state

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.player.stream.UpcomingPoll
import io.github.aedev.flow.ui.screens.player.state.UpcomingPollPolicy.Step
import org.junit.Test

class UpcomingPollPolicyTest {
    private fun poll(
        isPlayable: Boolean = false,
        isUpcoming: Boolean = true,
        broadcastId: String? = "1",
        pollDelayMs: Long? = 15_000L,
        heartbeatIntervalMs: Long? = null,
    ) = UpcomingPoll(isPlayable, isUpcoming, broadcastId, pollDelayMs, heartbeatIntervalMs)

    @Test
    fun `the wait is the server's poll delay, else its heartbeat, else thirty seconds`() {
        assertThat(UpcomingPollPolicy.delayMs(poll(pollDelayMs = 15_000L, heartbeatIntervalMs = 60_000L))).isEqualTo(15_000L)
        assertThat(UpcomingPollPolicy.delayMs(poll(pollDelayMs = null, heartbeatIntervalMs = 60_000L))).isEqualTo(60_000L)
        assertThat(UpcomingPollPolicy.delayMs(poll(pollDelayMs = null))).isEqualTo(30_000L)
        assertThat(UpcomingPollPolicy.delayMs(null)).isEqualTo(30_000L)
    }

    @Test
    fun `a zero or tiny delay never turns into a busy loop`() {
        assertThat(UpcomingPollPolicy.delayMs(poll(pollDelayMs = 0L))).isEqualTo(30_000L)
        assertThat(UpcomingPollPolicy.delayMs(poll(pollDelayMs = 200L))).isEqualTo(UpcomingPollPolicy.MIN_DELAY_MS)
    }

    @Test
    fun `a video still waiting is asked again and its broadcast is remembered`() {
        assertThat(UpcomingPollPolicy.next(knownBroadcastId = null, poll = poll())).isEqualTo(Step.Wait(15_000L, "1"))
        assertThat(UpcomingPollPolicy.next(knownBroadcastId = "1", poll = poll(broadcastId = null))).isEqualTo(Step.Wait(15_000L, "1"))
    }

    @Test
    fun `the full load runs once the video plays or a new broadcast replaces the old one`() {
        assertThat(UpcomingPollPolicy.next("1", poll(isPlayable = true, isUpcoming = false))).isEqualTo(Step.Load(true, "1"))
        assertThat(UpcomingPollPolicy.next("1", poll(broadcastId = "2"))).isEqualTo(Step.Load(true, "2"))
    }

    @Test
    fun `a video that stops waiting without playing is loaded once to show why`() {
        assertThat(UpcomingPollPolicy.next("1", poll(isUpcoming = false))).isEqualTo(Step.Load(false, "1"))
    }

    @Test
    fun `a failed call waits the default interval and keeps what it knew`() {
        assertThat(UpcomingPollPolicy.next("1", null)).isEqualTo(Step.Wait(30_000L, "1"))
    }
}
