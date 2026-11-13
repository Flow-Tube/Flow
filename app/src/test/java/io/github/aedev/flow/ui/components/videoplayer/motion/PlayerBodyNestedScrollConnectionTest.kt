package io.github.aedev.flow.ui.components.videoplayer.motion

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class PlayerBodyNestedScrollConnectionTest {
    private class Harness(
        expandedVideoHeight: Float = 600f,
        baseVideoHeight: Float = 600f,
        var scrollAwayEnabled: Boolean = true,
    ) {
        var heightFraction = 1f
        var portraitFs = 0f
        var scrollAway = 0f
        var enteredFullscreen = false

        val connection =
            PlayerBodyNestedScrollConnection(
                expandedVideoHeight = expandedVideoHeight,
                baseVideoHeight = baseVideoHeight,
                playerHeightFraction = { heightFraction },
                onPlayerHeightFractionChange = { heightFraction = it },
                portraitFsFraction = { portraitFs },
                onPortraitFsFractionChange = { portraitFs = it },
                animatePortraitFs = { target, _ -> portraitFs = target },
                portraitFsTravel = { 800f },
                portraitFsEnabled = { true },
                portraitFsActivationPx = { 50f },
                expandFraction = { 0f },
                onEnterPortraitFullscreen = { { enteredFullscreen = true } },
                scrollAwayEnabled = { scrollAwayEnabled },
                scrollAway = { scrollAway },
                scrollAwayRange = { baseVideoHeight + (expandedVideoHeight - baseVideoHeight) * heightFraction },
                onScrollAwayChange = { value, _ -> scrollAway = value },
            )

        fun fingerUp(dy: Float) = connection.onPreScroll(Offset(0f, -dy), NestedScrollSource.UserInput)

        fun leftoverDown(
            dy: Float,
            listConsumed: Float = 0f,
        ) = connection.onPostScroll(Offset(0f, listConsumed), Offset(0f, dy), NestedScrollSource.UserInput)

        fun endGesture() = runBlocking { connection.onPreFling(Velocity.Zero) }
    }

    @Test
    fun `scrolling up hides the video before the list moves`() {
        val harness = Harness()

        val consumed = harness.fingerUp(250f)

        assertThat(consumed.y).isEqualTo(-250f)
        assertThat(harness.scrollAway).isEqualTo(250f)
    }

    @Test
    fun `once the video is gone the list gets the rest of the scroll`() {
        val harness = Harness()

        val consumed = harness.fingerUp(900f)

        assertThat(consumed.y).isEqualTo(-600f)
        assertThat(harness.scrollAway).isEqualTo(600f)
        assertThat(harness.fingerUp(100f).y).isEqualTo(0f)
    }

    @Test
    fun `a tall video shrinks to sixteen-nine first, then scrolls away by its new height`() {
        val harness = Harness(expandedVideoHeight = 1000f, baseVideoHeight = 600f)

        val consumed = harness.fingerUp(500f)

        assertThat(harness.heightFraction).isEqualTo(0f)
        assertThat(harness.scrollAway).isEqualTo(100f)
        assertThat(consumed.y).isEqualTo(-500f)
    }

    @Test
    fun `scrolling down brings the video back only after the list is at its top`() {
        val harness = Harness()
        harness.fingerUp(600f)
        harness.endGesture()

        assertThat(harness.leftoverDown(0f, listConsumed = 300f)).isEqualTo(Offset.Zero)
        assertThat(harness.scrollAway).isEqualTo(600f)

        val consumed = harness.leftoverDown(200f)
        assertThat(consumed.y).isEqualTo(200f)
        assertThat(harness.scrollAway).isEqualTo(400f)
    }

    @Test
    fun `the video coming back never runs on into portrait fullscreen in the same gesture`() {
        val harness = Harness()
        harness.fingerUp(100f)
        harness.endGesture()

        harness.leftoverDown(100f)
        harness.leftoverDown(300f)

        assertThat(harness.scrollAway).isEqualTo(0f)
        assertThat(harness.portraitFs).isEqualTo(0f)
    }

    @Test
    fun `the portrait fullscreen pull still works once the video is back`() {
        val harness = Harness()

        harness.leftoverDown(200f)

        assertThat(harness.portraitFs).isGreaterThan(0f)
    }

    @Test
    fun `with the switch off nothing scrolls away and the list keeps every delta`() {
        val harness = Harness(scrollAwayEnabled = false)

        assertThat(harness.fingerUp(300f)).isEqualTo(Offset.Zero)
        assertThat(harness.scrollAway).isEqualTo(0f)
    }

    @Test
    fun `with the switch off a tall video still only shrinks`() {
        val harness = Harness(expandedVideoHeight = 1000f, baseVideoHeight = 600f, scrollAwayEnabled = false)

        val consumed = harness.fingerUp(500f)

        assertThat(consumed.y).isEqualTo(-400f)
        assertThat(harness.heightFraction).isEqualTo(0f)
        assertThat(harness.scrollAway).isEqualTo(0f)
    }
}
