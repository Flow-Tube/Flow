package io.github.aedev.flow.player.audio.eq

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.aedev.flow.data.audio.eq.EqSpatialSound
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

class SpatialStageTest {
    private val rate = 48_000
    private val afterTwoMs = rate * 2 / 1_000
    private val echoCeiling = 10.0.pow(-30.0 / 20)
    private val strengths = listOf(EqSpatialSound.LOW, EqSpatialSound.MEDIUM, EqSpatialSound.HIGH)

    @Test
    fun `no strength repeats a centred voice after two milliseconds`() {
        strengths.forEach { sound ->
            val (left, right) = impulse(sound, leftAmount = 1.0, rightAmount = 1.0)

            assertNoLateCopy(left, sound)
            assertNoLateCopy(right, sound)
        }
    }

    @Test
    fun `no strength repeats a one-sided sound after two milliseconds`() {
        strengths.forEach { sound ->
            val (left, right) = impulse(sound, leftAmount = 1.0, rightAmount = 0.0)
            val peak = maxOf(left.maxOf { abs(it) }, right.maxOf { abs(it) })

            assertThat(left.drop(afterTwoMs).maxOf { abs(it) } / peak).isLessThan(echoCeiling)
            assertThat(right.drop(afterTwoMs).maxOf { abs(it) } / peak).isLessThan(echoCeiling)
        }
    }

    @Test
    fun `off leaves every sample as it was`() {
        val stage = SpatialStage(rate).apply { setTarget(EqSpatialSound.OFF, rampFrames = 0) }
        val frames = List(2_000) { i -> doubleArrayOf((i % 37) / 37.0 - 0.5, (i % 23) / 23.0 - 0.5) }

        assertThat(stage.isIdle).isTrue()
        frames.forEach { input ->
            val frame = input.copyOf()
            stage.process(frame)
            assertThat(frame[0]).isWithin(1e-12).of(input[0])
            assertThat(frame[1]).isWithin(1e-12).of(input[1])
        }
    }

    private fun assertNoLateCopy(
        channel: List<Double>,
        sound: EqSpatialSound,
    ) {
        val peak = channel.maxOf { abs(it) }
        val late = channel.drop(afterTwoMs).maxOf { abs(it) }

        assertWithMessage("late copy at $sound").that(late / peak).isLessThan(echoCeiling)
    }

    private fun impulse(
        sound: EqSpatialSound,
        leftAmount: Double,
        rightAmount: Double,
    ): Pair<List<Double>, List<Double>> {
        val stage = SpatialStage(rate).apply { setTarget(sound, rampFrames = 0) }
        val left = ArrayList<Double>()
        val right = ArrayList<Double>()
        repeat(rate / 10) { i ->
            val frame = if (i == 0) doubleArrayOf(leftAmount, rightAmount) else doubleArrayOf(0.0, 0.0)
            stage.process(frame)
            left += frame[0]
            right += frame[1]
        }
        return left to right
    }
}
