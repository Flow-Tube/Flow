package io.github.aedev.flow.service

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import io.github.aedev.flow.player.factory.LoadControlFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(UnstableApi::class)
class MusicLoadControlTest {
    private val playerId = PlayerId("music")
    private val timeline = SinglePeriodTimeline(300_000_000L, true, false, false, null, MediaItem.Builder().setMediaId("song").build())
    private val periodId = MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0))
    private lateinit var control: MusicLoadControl

    @Before
    fun setUp() {
        control = MusicLoadControl(LoadControlFactory.forMusic(wholeSong = true), initiallySavesData = false)
        control.onPrepared(playerId)
    }

    @Test
    fun `without data saving the song keeps loading past the lean window`() {
        assertTrue(control.shouldContinueLoading(params(bufferedSeconds = 0)))
        assertTrue(control.shouldContinueLoading(params(bufferedSeconds = 45)))
        assertTrue(control.shouldContinueLoading(params(bufferedSeconds = 240)))
    }

    @Test
    fun `data saving stops at the lean window and waits until the buffer runs low`() {
        control.savesData = true

        assertTrue(control.shouldContinueLoading(params(bufferedSeconds = 0)))
        assertFalse(control.shouldContinueLoading(params(bufferedSeconds = 30)))
        assertFalse(control.shouldContinueLoading(params(bufferedSeconds = 20)))
        assertFalse(control.shouldContinueLoading(params(bufferedSeconds = 3)))
        assertTrue(control.shouldContinueLoading(params(bufferedSeconds = 2)))
        assertTrue(control.shouldContinueLoading(params(bufferedSeconds = 20)))
    }

    @Test
    fun `turning data saving off lets a capped song load to the end`() {
        control.savesData = true
        assertTrue(control.shouldContinueLoading(params(bufferedSeconds = 0)))
        assertFalse(control.shouldContinueLoading(params(bufferedSeconds = 30)))

        control.savesData = false

        assertTrue(control.shouldContinueLoading(params(bufferedSeconds = 31)))
    }

    @Test
    fun `the back buffer stays while saving data`() {
        control.savesData = true

        assertEquals(
            LoadControlFactory.forMusic(wholeSong = true).getBackBufferDurationUs(playerId),
            control.getBackBufferDurationUs(playerId),
        )
        assertTrue(control.retainBackBufferFromKeyframe(playerId))
    }

    @Test
    fun `every method the stock control implements is forwarded, not left to a throwing default`() {
        val forwarded =
            MusicLoadControl::class.java.declaredMethods
                .map { it.name to it.parameterTypes.toList() }
                .toSet()
        val missing =
            DefaultLoadControl::class.java.declaredMethods
                .filter {
                    java.lang.reflect.Modifier
                        .isPublic(it.modifiers) &&
                        LoadControl::class.java.methods.any { m -> m.name == it.name }
                }.map { it.name to it.parameterTypes.toList() }
                .filterNot { it in forwarded }

        assertEquals(emptyList<Any>(), missing)
    }

    private fun params(bufferedSeconds: Long) =
        LoadControl.Parameters(
            playerId,
            timeline,
            periodId,
            0L,
            bufferedSeconds * 1_000_000L,
            1f,
            true,
            false,
            0L,
            0L,
        )
}
