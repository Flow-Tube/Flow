package io.github.aedev.flow.ui.components.videoplayer.motion

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity

/**
 * Lets the body list drive three things while the player is expanded: a tall video shrinking to
 * 16:9 as the list scrolls up, then, when the player is set to scroll away, the video sliding off
 * the top with the page, and an overscroll pull at the top that slides the video to the centre of
 * the window for portrait fullscreen. Scrolling back down undoes them in reverse: the list returns
 * to its top first, then the video slides back, then a tall video grows. All of it is snapshot
 * state read only in the layout or draw phase.
 */
internal class PlayerBodyNestedScrollConnection(
    private val expandedVideoHeight: Float,
    private val baseVideoHeight: Float,
    private val playerHeightFraction: () -> Float,
    private val onPlayerHeightFractionChange: (Float) -> Unit,
    private val portraitFsFraction: () -> Float,
    private val onPortraitFsFractionChange: (Float) -> Unit,
    private val animatePortraitFs: suspend (target: Float, velocity: Float) -> Unit,
    private val portraitFsTravel: () -> Float,
    private val portraitFsEnabled: () -> Boolean,
    private val portraitFsActivationPx: () -> Float,
    private val expandFraction: () -> Float,
    private val onEnterPortraitFullscreen: () -> (() -> Unit)?,
    private val scrollAwayEnabled: () -> Boolean = { false },
    private val scrollAway: () -> Float = { 0f },
    private val scrollAwayRange: () -> Float = { 0f },
    private val onScrollAwayChange: (value: Float, range: Float) -> Unit = { _, _ -> },
) : NestedScrollConnection {
    private var listScrolledThisGesture = false
    private var pullAccum = 0f

    override fun onPreScroll(
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        val delta = available.y
        val portraitFraction = portraitFsFraction()
        if (source == NestedScrollSource.UserInput && delta < 0f && portraitFraction > 0f && portraitFsEnabled()) {
            val travel = portraitFsTravel()
            val maxConsumable = portraitFraction * travel
            val consumed = maxOf(delta, -maxConsumable)
            onPortraitFsFractionChange((portraitFraction + consumed / travel).coerceIn(0f, 1f))
            return Offset(0f, consumed)
        }
        if (delta >= 0f) return Offset.Zero
        var remaining = delta
        val playerDelta = expandedVideoHeight - baseVideoHeight
        val heightFraction = playerHeightFraction()
        if (heightFraction > 0f && playerDelta > 1f) {
            val consumed = maxOf(remaining, -heightFraction * playerDelta)
            onPlayerHeightFractionChange((heightFraction + consumed / playerDelta).coerceIn(0f, 1f))
            remaining -= consumed
        }
        if (remaining < 0f && scrollAwayEnabled()) {
            val range = scrollAwayRange()
            val current = scrollAway()
            val room = range - current
            if (room > 0f) {
                val consumed = minOf(-remaining, room)
                onScrollAwayChange(current + consumed, range)
                remaining += consumed
            }
        }
        return Offset(0f, delta - remaining)
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        if (consumed.y != 0f) listScrolledThisGesture = true
        val delta = available.y
        val current = scrollAway()
        if (delta > 0f && current > 0f) {
            // Bringing the video back is this gesture's job, so the same pull can't run on into fullscreen.
            listScrolledThisGesture = true
            val consumable = minOf(delta, current)
            onScrollAwayChange(current - consumable, scrollAwayRange())
            return Offset(0f, consumable)
        }
        val playerDelta = expandedVideoHeight - baseVideoHeight
        val heightFraction = playerHeightFraction()
        if (delta > 0 && heightFraction < 1f && playerDelta > 1f) {
            val maxConsumable = (1f - heightFraction) * playerDelta
            val consumable = minOf(delta, maxConsumable)
            onPlayerHeightFractionChange((heightFraction + consumable / playerDelta).coerceIn(0f, 1f))
            return Offset(0f, consumable)
        }
        val canPull =
            source == NestedScrollSource.UserInput &&
                !listScrolledThisGesture &&
                portraitFsEnabled() &&
                expandFraction() < 0.05f
        val portraitFraction = portraitFsFraction()
        if (delta > 0f && portraitFraction < 1f && canPull) {
            pullAccum += delta
            val past = pullAccum - portraitFsActivationPx()
            if (past <= 0f) return Offset(0f, delta)
            val travel = portraitFsTravel()
            val effective = minOf(delta, past)
            val maxConsumable = (1f - portraitFraction) * travel
            val consumable = minOf(effective, maxConsumable)
            onPortraitFsFractionChange((portraitFraction + consumable / travel).coerceIn(0f, 1f))
            return Offset(0f, delta)
        }
        return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        val frac = portraitFsFraction()
        listScrolledThisGesture = false
        pullAccum = 0f
        if (frac <= 0f) return Velocity.Zero
        if (frac >= 1f) {
            onEnterPortraitFullscreen()?.invoke()
            return available
        }
        val shouldEnter = shouldEnterPortraitFullscreen(fraction = frac, velocityY = available.y)
        animatePortraitFs(if (shouldEnter) 1f else 0f, fractionVelocity(available.y, portraitFsTravel()))
        if (shouldEnter) onEnterPortraitFullscreen()?.invoke()
        return available
    }
}
