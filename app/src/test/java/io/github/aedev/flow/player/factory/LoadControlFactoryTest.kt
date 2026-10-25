package io.github.aedev.flow.player.factory

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import io.github.aedev.flow.player.config.PlayerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Shorts and music profiles are fixed constants rather than user preferences, so nothing else
 * re-checks them: [io.github.aedev.flow.data.local.BufferDurationsTest] only covers the video path,
 * whose durations come from DataStore. These tests are what stops an edit to the constants from
 * reaching a device as the #788 class of failure — `setBufferDurationsMs` throws while the player is
 * being built, which surfaces as a crash on every launch rather than as degraded buffering.
 */
@UnstableApi
class LoadControlFactoryTest {
    @Test
    fun `shorts profile is accepted by the load control`() {
        LoadControlFactory.forShorts()
    }

    @Test
    fun `music profile is accepted by the load control`() {
        LoadControlFactory.forMusic()
    }

    @Test
    fun `shorts constants satisfy the load control contract before any coercion`() {
        assertContractHolds(
            profile = "shorts",
            minMs = PlayerConfig.SHORTS_MIN_BUFFER_MS,
            maxMs = PlayerConfig.SHORTS_MAX_BUFFER_MS,
            playbackMs = PlayerConfig.SHORTS_BUFFER_FOR_PLAYBACK_MS,
            rebufferMs = PlayerConfig.SHORTS_BUFFER_FOR_REBUFFER_MS,
        )
    }

    @Test
    fun `music constants satisfy the load control contract before any coercion`() {
        assertContractHolds(
            profile = "music",
            minMs = PlayerConfig.MUSIC_MIN_BUFFER_MS,
            maxMs = PlayerConfig.MUSIC_MAX_BUFFER_MS,
            playbackMs = PlayerConfig.MUSIC_BUFFER_FOR_PLAYBACK_MS,
            rebufferMs = PlayerConfig.MUSIC_BUFFER_FOR_REBUFFER_MS,
        )
    }

    @Test
    fun `the stock profiles keep the byte targets they always had`() {
        fun bytes(memoryClassMb: Int) =
            LoadControlFactory.videoBudget(memoryClassMb, isLowRamDevice = false, requestedMaxMs = 50_000).targetBufferBytes

        assertEquals(PlayerConfig.LOW_MEMORY_MAIN_TARGET_BUFFER_BYTES, bytes(256))
        assertEquals(PlayerConfig.MID_MEMORY_MAIN_TARGET_BUFFER_BYTES, bytes(384))
        assertEquals(PlayerConfig.MAIN_TARGET_BUFFER_BYTES, bytes(512))
    }

    @Test
    fun `a long custom buffer is honoured up to a fifth of the heap`() {
        val pixelHeap = LoadControlFactory.videoBudget(memoryClassMb = 256, isLowRamDevice = false, requestedMaxMs = 180_000)
        val bigHeap = LoadControlFactory.videoBudget(memoryClassMb = 1024, isLowRamDevice = false, requestedMaxMs = 180_000)

        assertEquals(180_000, pixelHeap.maxSafeMaxMs)
        assertEquals(256 * MB / 5, pixelHeap.targetBufferBytes)
        assertEquals(PlayerConfig.MAX_LONG_BUFFER_BYTES, bigHeap.targetBufferBytes)
        assertEquals(PlayerConfig.LOW_MEMORY_MAX_SAFE_MAIN_MIN_BUFFER_MS, pixelHeap.maxSafeMinMs)
    }

    @Test
    fun `a low-RAM device keeps its short buffer whatever was asked`() {
        val budget = LoadControlFactory.videoBudget(memoryClassMb = 192, isLowRamDevice = true, requestedMaxMs = 180_000)

        assertEquals(PlayerConfig.LOW_MEMORY_MAX_SAFE_MAIN_BUFFER_MS, budget.maxSafeMaxMs)
        assertEquals(PlayerConfig.LOW_MEMORY_MAIN_TARGET_BUFFER_BYTES, budget.targetBufferBytes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `the load control still rejects a profile that inverts min and rebuffer`() {
        // Keeps the checks above from passing vacuously.
        DefaultLoadControl.Builder().setBufferDurationsMs(1_500, 8_000, 250, 5_000)
    }

    /**
     * Asserts the raw constants, not the coerced output of [LoadControlFactory.build] — the coercion
     * is a backstop, and a profile that only survives because of it has drifted from its intent.
     */
    private fun assertContractHolds(
        profile: String,
        minMs: Int,
        maxMs: Int,
        playbackMs: Int,
        rebufferMs: Int,
    ) {
        assertTrue("$profile: max ($maxMs) must be >= min ($minMs)", maxMs >= minMs)
        assertTrue("$profile: min ($minMs) must be >= playback ($playbackMs)", minMs >= playbackMs)
        assertTrue("$profile: min ($minMs) must be >= rebuffer ($rebufferMs)", minMs >= rebufferMs)

        DefaultLoadControl.Builder().setBufferDurationsMs(minMs, maxMs, playbackMs, rebufferMs)
    }

    private companion object {
        const val MB = 1024 * 1024
    }
}
