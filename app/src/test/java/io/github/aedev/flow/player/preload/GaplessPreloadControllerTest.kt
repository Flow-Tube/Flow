package io.github.aedev.flow.player.preload

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.player.stream.model.StreamType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Preloading is what makes advancing to the next video gapless, and every one of its guards exists
 * to stop a second window being appended when the session has already moved on -- an appended item
 * for the wrong video plays the wrong video. The guards were unreachable from a test while this
 * lived inside `EnhancedPlayerManager`.
 */
@UnstableApi
@OptIn(ExperimentalCoroutinesApi::class)
class GaplessPreloadControllerTest {
    @Test
    fun `a downloaded next video is not preloaded, so advance plays the file`() =
        runTest {
            val player = playerWith()
            val harness =
                PreloadHarness(
                    backgroundScope,
                    player = player,
                    target = PreloadTarget(video("next"), fromQueue = true),
                    resolve = { resolved("next") },
                    downloaded = setOf("next"),
                )

            harness.controller.schedule()
            runCurrent()

            verify(exactly = 0) { player.addMediaSource(any<MediaSource>()) }
            assertThat(harness.resolveCalls).isEqualTo(0)
            assertThat(harness.controller.preloaded).isNull()
        }

    @Test
    fun `preloading appends the resolved next video as a second window`() =
        runTest {
            val player = playerWith()
            val harness =
                PreloadHarness(
                    backgroundScope,
                    player = player,
                    target = PreloadTarget(video("next"), fromQueue = true),
                    resolve = { resolved("next") },
                )

            harness.controller.schedule()
            runCurrent()

            verify { player.addMediaSource(any<MediaSource>()) }
            assertThat(
                harness.controller.preloaded
                    ?.data
                    ?.enrichedVideo
                    ?.id,
            ).isEqualTo("next")
            assertThat(harness.controller.preloaded?.fromQueue).isTrue()
        }

    @Test
    fun `a request with nothing to play next removes the appended video`() =
        runTest {
            var windows = 1
            val player =
                mockk<ExoPlayer>(relaxed = true) {
                    every { getMediaItemCount() } answers { windows }
                    every { getCurrentMediaItem() } returns mockk<MediaItem>()
                    every { getCurrentMediaItemIndex() } returns 0
                    every { addMediaSource(any<MediaSource>()) } answers { windows = 2 }
                }
            val harness =
                PreloadHarness(
                    backgroundScope,
                    player = player,
                    target = PreloadTarget(video("next"), fromQueue = false),
                    resolve = { resolved("next") },
                )
            harness.controller.schedule()
            runCurrent()
            assertThat(harness.controller.preloaded).isNotNull()

            harness.target = null
            harness.controller.request("autoplay-off")

            verify(exactly = 1) { player.removeMediaItem(1) }
            assertThat(harness.controller.preloaded).isNull()
        }

    @Test
    fun `looping never preloads, because the same video repeats`() =
        runTest {
            val harness =
                PreloadHarness(
                    backgroundScope,
                    player = playerWith(),
                    target = PreloadTarget(video("next"), fromQueue = false),
                    isLooping = true,
                    resolve = { resolved("next") },
                )

            harness.controller.schedule()
            runCurrent()

            assertThat(harness.resolveCalls).isEqualTo(0)
            assertThat(harness.controller.preloaded).isNull()
        }

    @Test
    fun `a live stream is never preloaded`() =
        runTest {
            val harness =
                PreloadHarness(
                    backgroundScope,
                    player = playerWith(),
                    target = PreloadTarget(video("next"), fromQueue = false),
                    isLive = true,
                    resolve = { resolved("next") },
                )

            harness.controller.schedule()
            runCurrent()

            assertThat(harness.resolveCalls).isEqualTo(0)
        }

    @Test
    fun `a next video that resolves as live is dropped instead of appended`() =
        runTest {
            val player = playerWith()
            val harness =
                PreloadHarness(
                    backgroundScope,
                    player = player,
                    target = PreloadTarget(video("next"), fromQueue = false),
                    resolve = { resolved("next", streamType = StreamType.LIVE_STREAM) },
                )

            harness.controller.schedule()
            runCurrent()

            verify(exactly = 0) { player.addMediaSource(any<MediaSource>()) }
            assertThat(harness.controller.preloaded).isNull()
        }

    @Test
    fun `a window is never appended twice`() =
        runTest {
            val player = playerWith(mediaItemCount = 2)
            val harness =
                PreloadHarness(
                    backgroundScope,
                    player = player,
                    target = PreloadTarget(video("next"), fromQueue = false),
                    resolve = { resolved("next") },
                )

            harness.controller.schedule()
            runCurrent()

            assertThat(harness.resolveCalls).isEqualTo(0)
            verify(exactly = 0) { player.addMediaSource(any<MediaSource>()) }
        }

    @Test
    fun `nothing is preloaded while no video is playing`() =
        runTest {
            val harness =
                PreloadHarness(
                    backgroundScope,
                    player = playerWith(),
                    currentVideoId = null,
                    target = PreloadTarget(video("next"), fromQueue = false),
                    resolve = { resolved("next") },
                )

            harness.controller.schedule()
            runCurrent()

            assertThat(harness.resolveCalls).isEqualTo(0)
        }

    @Test
    fun `a failed attempt does not block later ones for the same video`() =
        runTest {
            val harness =
                PreloadHarness(
                    backgroundScope,
                    player = playerWith(),
                    target = PreloadTarget(video("next"), fromQueue = false),
                    resolve = { null },
                )

            harness.controller.schedule()
            runCurrent()
            val afterFirst = harness.resolveCalls

            // A failure has to release the in-flight marker, or gapless stays off for the rest of
            // the session even once the network recovers.
            harness.controller.schedule()
            runCurrent()

            assertThat(afterFirst).isEqualTo(1)
            assertThat(harness.resolveCalls).isEqualTo(2)
        }

    @Test
    fun `a video that changed while resolving is not appended`() =
        runTest {
            val player = playerWith()
            val harness =
                PreloadHarness(
                    backgroundScope,
                    player = player,
                    target = PreloadTarget(video("next"), fromQueue = false),
                )
            val controller =
                GaplessPreloadController(
                    scope = backgroundScope,
                    player = { player },
                    context = { mockk(relaxed = true) },
                    currentVideoId = { harness.currentVideoId },
                    nextTarget = { harness.target },
                    isLooping = { false },
                    isLiveStream = { false },
                    resolveStreams = { _, _ ->
                        // The user skipped ahead while the network call was in flight.
                        harness.currentVideoId = "something-else"
                        resolved("next")
                    },
                    hasLocalCopy = { false },
                    buildMediaSource = { _, _ -> mockk(relaxed = true) },
                    isForegroundVideo = { false },
                    isDataSaverOn = { false },
                    nextVideoPreload = { ExoPlayer.PreloadConfiguration.DEFAULT },
                    log = {},
                )

            controller.schedule()
            runCurrent()

            verify(exactly = 0) { player.addMediaSource(any<MediaSource>()) }
            assertThat(controller.preloaded).isNull()
        }

    @Test
    fun `a failed resolve is retried and eventually given up on`() =
        runTest {
            val harness =
                PreloadHarness(
                    backgroundScope,
                    player = playerWith(),
                    target = PreloadTarget(video("next"), fromQueue = false),
                    resolve = { null },
                )

            harness.controller.schedule()
            runCurrent()
            assertThat(harness.resolveCalls).isEqualTo(1)

            // Three retries, then the attempt is abandoned rather than looping forever.
            repeat(4) {
                advanceTimeBy(11_000)
                runCurrent()
            }

            assertThat(harness.resolveCalls).isEqualTo(4)
            assertThat(harness.logs).contains("schedulePreloadRetry giving up next=next")
        }

    @Test
    fun `a next video YouTube removed is not retried`() =
        runTest {
            val harness =
                PreloadHarness(
                    backgroundScope,
                    player = playerWith(),
                    target = PreloadTarget(video("next"), fromQueue = false),
                    resolve = { null },
                    gone = setOf("next"),
                )

            harness.controller.schedule()
            runCurrent()
            advanceTimeBy(40_000)
            runCurrent()

            assertThat(harness.resolveCalls).isEqualTo(1)
        }

    @Test
    fun `re-targeting drops the window appended for the old next video`() =
        runTest {
            val player = playerWith(mediaItemCount = 1)
            val harness =
                PreloadHarness(
                    backgroundScope,
                    player = player,
                    target = PreloadTarget(video("first"), fromQueue = false),
                    resolve = { resolved(it.id) },
                )

            harness.controller.schedule()
            runCurrent()
            assertThat(
                harness.controller.preloaded
                    ?.data
                    ?.enrichedVideo
                    ?.id,
            ).isEqualTo("first")

            every { player.getMediaItemCount() } returns 2
            harness.target = PreloadTarget(video("second"), fromQueue = false)
            harness.controller.request("queue-change")
            runCurrent()

            // The appended window belongs to a video that is no longer next, so it has to go.
            verify { player.removeMediaItem(1) }
        }

    @Test
    fun `consuming hands over the preload and leaves the window playing`() =
        runTest {
            val player = playerWith()
            val harness =
                PreloadHarness(
                    backgroundScope,
                    player = player,
                    target = PreloadTarget(video("next"), fromQueue = true),
                    resolve = { resolved("next") },
                )

            harness.controller.schedule()
            runCurrent()

            val consumed = harness.controller.consume()

            assertThat(consumed?.data?.enrichedVideo?.id).isEqualTo("next")
            assertThat(harness.controller.preloaded).isNull()
            assertThat(harness.controller.consume()).isNull()
            verify(exactly = 0) { player.removeMediaItem(any<Int>()) }
        }

    @Test
    fun `clearing removes the appended window`() =
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
            every { player.getMediaItemCount() } returns 2

            harness.controller.clear()

            assertThat(harness.controller.preloaded).isNull()
            verify { player.removeMediaItem(1) }
        }
}
