package io.github.aedev.flow.player.live

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test

class LiveStreamEndTest {
    private val scope = TestScope()
    private var checks = 0
    private var stillLive: Boolean? = true
    private val end =
        LiveStreamEnd(segmentDurationMs = 5_000, scope = scope) {
            checks++
            stillLive
        }

    private fun head(
        sequence: Long,
        atMs: Long,
    ) = LiveHead(sequence = sequence, mediaTimeMs = sequence * 5_000, serverTimeMs = atMs)

    @Test
    fun `an advancing head is never checked`() {
        (0L..20L).forEach { end.observe(head(100 + it, it * 5_000)) }
        scope.advanceUntilIdle()
        assertThat(checks).isEqualTo(0)
    }

    @Test
    fun `a head still for three segments is checked once, and again every 30 seconds`() {
        end.observe(head(100, 0))
        end.observe(head(100, 14_000))
        scope.advanceUntilIdle()
        assertThat(checks).isEqualTo(0)

        end.observe(head(100, 15_000))
        end.observe(head(100, 20_000))
        scope.advanceUntilIdle()
        assertThat(checks).isEqualTo(1)
        assertThat(end.lastSequenceOfEndedStream).isNull()

        end.observe(head(100, 45_000))
        scope.advanceUntilIdle()
        assertThat(checks).isEqualTo(2)
    }

    @Test
    fun `a stream the player response no longer calls live ends at its last segment`() {
        stillLive = false
        end.observe(head(100, 0))
        end.observe(head(100, 15_000))
        scope.advanceUntilIdle()

        assertThat(end.lastSequenceOfEndedStream).isEqualTo(100)
    }

    @Test
    fun `an unanswered check does not end the stream`() {
        stillLive = null
        end.observe(head(100, 0))
        end.observe(head(100, 15_000))
        scope.advanceUntilIdle()

        assertThat(end.lastSequenceOfEndedStream).isNull()
    }
}
