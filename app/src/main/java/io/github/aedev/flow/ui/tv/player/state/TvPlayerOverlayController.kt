package io.github.aedev.flow.ui.tv.player.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the TV player shows over the video. [SEEK] is the seek bar alone, while Left or Right seek with
 * the controls hidden. In [PANEL] one of the phone player's sheets is open beside it; which one lives
 * in the shared player screen state.
 */
enum class TvOverlayMode { HIDDEN, SEEK, TRANSPORT, PANEL }

data class TvOverlayState(
    val mode: TvOverlayMode = TvOverlayMode.HIDDEN,
    val lastInteractionAtMs: Long = 0L,
)

/**
 * Overlay state machine for the TV player, free of Compose/Android types.
 * Auto-hide is *computed* here ([autoHideDeadline]) but executed by the UI with
 * a single restartable delay — never a polling loop.
 */
class TvPlayerOverlayController(
    private val nowMs: () -> Long,
) {
    private val _state = MutableStateFlow(TvOverlayState())
    val state: StateFlow<TvOverlayState> = _state.asStateFlow()

    fun onUserInteraction() {
        _state.update { it.copy(lastInteractionAtMs = nowMs()) }
    }

    fun showTransport() {
        _state.update { it.copy(mode = TvOverlayMode.TRANSPORT, lastInteractionAtMs = nowMs()) }
    }

    /** The seek bar alone, unless the controls are already up: seeking from them keeps them. */
    fun showSeek() {
        _state.update { current ->
            when (current.mode) {
                TvOverlayMode.HIDDEN, TvOverlayMode.SEEK -> current.copy(mode = TvOverlayMode.SEEK, lastInteractionAtMs = nowMs())
                else -> current
            }
        }
    }

    fun hide() {
        _state.update { it.copy(mode = TvOverlayMode.HIDDEN) }
    }

    fun openPanel() {
        _state.update { it.copy(mode = TvOverlayMode.PANEL, lastInteractionAtMs = nowMs()) }
    }

    /** Closes the open panel back to the transport. */
    fun closePanel() {
        _state.update { current ->
            if (current.mode != TvOverlayMode.PANEL) return@update current
            current.copy(mode = TvOverlayMode.TRANSPORT, lastInteractionAtMs = nowMs())
        }
    }

    /** Walks PANEL → TRANSPORT → HIDDEN. Returns false when Back should close the player. */
    fun onBack(): Boolean =
        when (_state.value.mode) {
            TvOverlayMode.PANEL -> {
                closePanel()
                true
            }

            TvOverlayMode.TRANSPORT, TvOverlayMode.SEEK -> {
                hide()
                true
            }

            TvOverlayMode.HIDDEN -> {
                false
            }
        }

    /**
     * Epoch-ms moment the overlay should hide, or null when it must stay up (hidden or panel mode, an
     * active scrub, or the transport while paused). The seek bar alone goes soon after the last seek,
     * paused or not, so it never sits over the picture (#998).
     */
    fun autoHideDeadline(
        isPlaying: Boolean,
        isScrubbing: Boolean,
    ): Long? {
        val current = _state.value
        if (isScrubbing) return null
        return when (current.mode) {
            TvOverlayMode.SEEK -> current.lastInteractionAtMs + SEEK_HIDE_DELAY_MS
            TvOverlayMode.TRANSPORT -> if (isPlaying) current.lastInteractionAtMs + AUTO_HIDE_DELAY_MS else null
            else -> null
        }
    }

    companion object {
        const val AUTO_HIDE_DELAY_MS = 5_000L
        const val SEEK_HIDE_DELAY_MS = 1_000L
    }
}
