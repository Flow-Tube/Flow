package io.github.aedev.flow.ui.components.musicplayer.sheet

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

private const val ANCHOR_DISMISSED = 0
private const val ANCHOR_COLLAPSED = 1
private const val ANCHOR_EXPANDED = 2

/**
 * Logical state for the unified music player sheet. Position ([translationY], px from container
 * top) and morph progress ([expansionFraction], 0..1) are separate animatables driven together by
 * the sheet's motion controller; this class only decides where the sheet wants to be.
 */
@Stable
class MusicPlayerSheetState internal constructor(
    initialAnchor: Int,
    private val onAnchorPersist: (Int) -> Unit,
) {
    internal val translationY = Animatable(0f)
    internal val expansionFraction = Animatable(if (initialAnchor == ANCHOR_EXPANDED) 1f else 0f)
    internal val mutex = MutatorMutex()

    internal var anchor by mutableIntStateOf(initialAnchor)
        private set

    /** Bumped on every move request so a settle re-runs even when the anchor is unchanged. */
    internal var settleRequestId by mutableIntStateOf(0)
        private set

    internal var dismissSettled by mutableStateOf(initialAnchor == ANCHOR_DISMISSED)

    internal var pendingSettleVelocity = 0f
        private set
    internal var pendingSettleDamping: Float? = null
        private set
    internal var pendingSettleSquash: Float? = null
        private set

    val isDismissed: Boolean by derivedStateOf { anchor == ANCHOR_DISMISSED }
    val isCollapsed: Boolean by derivedStateOf { anchor == ANCHOR_COLLAPSED }
    val isExpanded: Boolean by derivedStateOf { anchor == ANCHOR_EXPANDED }

    /** Flips only when the morph crosses the midpoint; safe to read in composition. */
    val isImmersive: Boolean by derivedStateOf { expansionFraction.value > 0.5f }

    /** Whether the lyrics page covers the expanded player. It paints a dark artwork backdrop on every theme. */
    var isLyricsShown: Boolean by mutableStateOf(false)
        internal set

    /** Raw morph progress; avoid reading in composition — prefer draw/layout-phase lambdas. */
    val progress: Float get() = expansionFraction.value

    fun expand() = moveTo(ANCHOR_EXPANDED)

    fun collapse() = moveTo(ANCHOR_COLLAPSED)

    fun dismiss() = moveTo(ANCHOR_DISMISSED)

    internal fun settleFromGesture(
        targetExpanded: Boolean,
        velocity: Float,
        dampingRatio: Float,
        squash: Float,
    ) {
        pendingSettleVelocity = velocity
        pendingSettleDamping = dampingRatio
        pendingSettleSquash = squash
        moveTo(if (targetExpanded) ANCHOR_EXPANDED else ANCHOR_COLLAPSED)
    }

    internal fun consumePendingSettle(): Triple<Float, Float?, Float?> {
        val result = Triple(pendingSettleVelocity, pendingSettleDamping, pendingSettleSquash)
        pendingSettleVelocity = 0f
        pendingSettleDamping = null
        pendingSettleSquash = null
        return result
    }

    private fun moveTo(target: Int) {
        if (target != ANCHOR_DISMISSED) dismissSettled = false
        if (anchor != target) {
            anchor = target
            onAnchorPersist(target)
        }
        settleRequestId++
    }
}

@Composable
fun rememberMusicPlayerSheetState(): MusicPlayerSheetState {
    var savedAnchor by rememberSaveable { mutableIntStateOf(ANCHOR_DISMISSED) }
    return remember {
        MusicPlayerSheetState(
            initialAnchor = savedAnchor,
            onAnchorPersist = { savedAnchor = it },
        )
    }
}
