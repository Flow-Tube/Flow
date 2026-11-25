package io.github.aedev.flow.player.preload

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import androidx.media3.exoplayer.source.MediaSource
import com.google.common.truth.Truth.assertThat
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** In the foreground the next video waits for the end of this one; background playback still preloads at once. */
@UnstableApi
@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundPreloadTest {
    private fun foregroundPlayer(
        durationMs: Long,
        positionMs: Long,
        message: PlayerMessage = armedMessage(),
        target: CapturingSlot<PlayerMessage.Target> = slot(),
    ): ExoPlayer =
        playerWith().apply {
            every { duration } returns durationMs
            every { currentPosition } returns positionMs
            every { createMessage(capture(target)) } returns message
        }

    private fun armedMessage(): PlayerMessage =
        mockk(relaxed = true) {
            every { setLooper(any()) } returns this
            every { setPosition(any<Int>(), any()) } returns this
            every { setDeleteAfterDelivery(any()) } returns this
            every { send() } returns this
        }

    @Test
    fun `in the foreground the next video waits for the last minute, armed on the player's own clock`() =
        runTest {
            val message = armedMessage()
            val player = foregroundPlayer(durationMs = 600_000L, positionMs = 5_000L, message = message)
            val harness =
                PreloadHarness(
                    backgroundScope,
                    player = player,
                    target = PreloadTarget(video("next"), fromQueue = false),
                    resolve = { resolved("next") },
                    foreground = true,
                )

            harness.controller.schedule()
            runCurrent()

            assertThat(harness.resolveCalls).isEqualTo(0)
            verify { message.setPosition(0, 540_000L) }
            verify { message.send() }
        }

    @Test
    fun `reaching the preload point starts the preload`() =
        runTest {
            val target = slot<PlayerMessage.Target>()
            val player = foregroundPlayer(durationMs = 600_000L, positionMs = 5_000L, target = target)
            val harness =
                PreloadHarness(
                    backgroundScope,
                    player = player,
                    target = PreloadTarget(video("next"), fromQueue = false),
                    resolve = { resolved("next") },
                    foreground = true,
                )
            harness.controller.schedule()
            runCurrent()

            target.captured.handleMessage(0, null)
            runCurrent()

            assertThat(harness.resolveCalls).isEqualTo(1)
            assertThat(harness.controller.preloaded).isNotNull()
        }

    @Test
    fun `a seek past the preload point starts the preload the message would have missed`() =
        runTest {
            var position = 5_000L
            val message = armedMessage()
            val player =
                foregroundPlayer(durationMs = 600_000L, positionMs = 0L, message = message).apply {
                    every { currentPosition } answers { position }
                }
            val harness =
                PreloadHarness(
                    backgroundScope,
                    player = player,
                    target = PreloadTarget(video("next"), fromQueue = false),
                    resolve = { resolved("next") },
                    foreground = true,
                )
            harness.controller.schedule()
            runCurrent()

            position = 580_000L
            harness.controller.onSeek()
            runCurrent()

            verify { message.cancel() }
            assertThat(harness.resolveCalls).isEqualTo(1)
        }

    @Test
    fun `background and audio-only playback still preload at once, for the screen-off advance`() =
        runTest {
            val player = foregroundPlayer(durationMs = 600_000L, positionMs = 5_000L)
            val harness =
                PreloadHarness(
                    backgroundScope,
                    player = player,
                    target = PreloadTarget(video("next"), fromQueue = false),
                    resolve = { resolved("next") },
                    foreground = false,
                    dataSaver = true,
                )

            harness.controller.schedule()
            runCurrent()

            assertThat(harness.resolveCalls).isEqualTo(1)
            verify(exactly = 0) { player.createMessage(any()) }
        }

    @Test
    fun `Data Saver skips the foreground preload, read when it is scheduled`() =
        runTest {
            val player = foregroundPlayer(durationMs = 600_000L, positionMs = 590_000L)
            val harness =
                PreloadHarness(
                    backgroundScope,
                    player = player,
                    target = PreloadTarget(video("next"), fromQueue = false),
                    resolve = { resolved("next") },
                    foreground = true,
                    dataSaver = true,
                )

            harness.controller.schedule()
            runCurrent()
            assertThat(harness.resolveCalls).isEqualTo(0)

            harness.dataSaver = false
            harness.controller.schedule()
            runCurrent()
            assertThat(harness.resolveCalls).isEqualTo(1)
        }

    @Test
    fun `the appended video's media preload is read at append time`() =
        runTest {
            val player = playerWith()
            val harness =
                PreloadHarness(
                    backgroundScope,
                    player = player,
                    target = PreloadTarget(video("next"), fromQueue = false),
                    resolve = { resolved("next") },
                )

            harness.controller.schedule()
            runCurrent()

            verifyOrder {
                player.preloadConfiguration = harness.preloadConfiguration
                player.addMediaSource(any<MediaSource>())
            }
        }
}
