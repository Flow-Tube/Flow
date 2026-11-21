package io.github.aedev.flow.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.local.FeedAutoplay
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.player.preview.FeedPreviewConditions
import io.github.aedev.flow.player.preview.PreviewNetwork
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Whether a Home preview may play, and on how slow a link. */
internal data class HomePreviewGateState(
    val open: Boolean = false,
    val slowNetwork: Boolean = false,
)

/**
 * The muted previews on Home's feed cards. Its `init` does nothing: the gate's system listeners
 * register only while Home collects [gate], and only when the setting is on.
 */
@HiltViewModel
internal class HomePreviewViewModel
    @Inject
    constructor(
        preferences: PlayerPreferences,
        private val conditions: FeedPreviewConditions,
    ) : ViewModel() {
        private val foreground = MutableStateFlow(false)
        private val _candidate = MutableStateFlow<String?>(null)

        /** The card the feed's layout picked; it plays once the dwell is over. */
        val candidate: StateFlow<String?> = _candidate.asStateFlow()

        @OptIn(ExperimentalCoroutinesApi::class)
        val gate: StateFlow<HomePreviewGateState> =
            preferences.feedAutoplay
                .distinctUntilChanged()
                .flatMapLatest { autoplay ->
                    if (autoplay == FeedAutoplay.OFF) {
                        flowOf(HomePreviewSignals(autoplay) to PreviewNetwork(onWifi = false, slow = false))
                    } else {
                        signals(autoplay)
                    }
                }.map { (signals, network) ->
                    HomePreviewGateState(open = HomePreviewGate.blockOf(signals) == null, slowNetwork = network.slow)
                }.distinctUntilChanged()
                .onEach { if (!it.open) onCandidate(null) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(GATE_SUBSCRIPTION_TIMEOUT_MS), HomePreviewGateState())

        private fun signals(autoplay: FeedAutoplay): Flow<Pair<HomePreviewSignals, PreviewNetwork>> {
            val device =
                combine(
                    conditions.network(),
                    conditions.accessibilityOn(),
                    conditions.batterySaver(),
                    conditions.thermalSevere(),
                ) { network, accessibility, batterySaver, thermal ->
                    HomePreviewSignals(
                        autoplay = autoplay,
                        onWifi = network.onWifi,
                        accessibilityOn = accessibility,
                        batterySaver = batterySaver,
                        thermalSevere = thermal,
                        lowRamDevice = conditions.lowRamDevice,
                    ) to network
                }
            return combine(
                device,
                conditions.mainPlayerActive(),
                conditions.casting(),
                foreground,
            ) { (signals, network), main, casting, fg ->
                signals.copy(mainPlayerActive = main, casting = casting, foreground = fg) to network
            }
        }

        /** Home resumed with window focus; anything less stops a preview at once. */
        fun setForeground(value: Boolean) {
            foreground.value = value
            if (!value) onCandidate(null)
        }

        fun onCandidate(videoId: String?) {
            _candidate.value = videoId
        }

        private companion object {
            const val GATE_SUBSCRIPTION_TIMEOUT_MS = 5_000L
        }
    }
