package io.github.aedev.flow.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.local.FeedAutoplay
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.player.PlaybackTarget
import io.github.aedev.flow.player.preview.FeedPreviewConditions
import io.github.aedev.flow.player.preview.FeedPreviewPlayer
import io.github.aedev.flow.player.preview.FeedPreviewPlayerListener
import io.github.aedev.flow.player.preview.FeedPreviewStreams
import io.github.aedev.flow.player.preview.PreviewNetwork
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
        private val streams: FeedPreviewStreams,
        private val player: FeedPreviewPlayer,
    ) : ViewModel(),
        FeedPreviewPlayerListener {
        private val foreground = MutableStateFlow(false)
        private val _candidate = MutableStateFlow<String?>(null)
        private val _state = MutableStateFlow(HomePreviewState())
        private val _surfacePlayer = MutableStateFlow<Player?>(null)
        private val surfaceOwner = MutableStateFlow<String?>(null)
        private var job: Job? = null
        private var muted = true
        private var captionsChoice: Boolean? = null
        private val done = HashSet<String>()
        private val stoppedAt =
            object : LinkedHashMap<String, Long>(MAX_REMEMBERED, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > MAX_REMEMBERED
            }

        /** The card the feed's layout picked; it plays once the dwell is over. */
        val candidate: StateFlow<String?> = _candidate.asStateFlow()

        val state: StateFlow<HomePreviewState> = _state.asStateFlow()

        /** The id every card's overlay checks; only the card it names draws anything. */
        val activeVideoId: StateFlow<String?> = _candidate.asStateFlow()

        /** The player the active card's surface shows, null when there is none. */
        val surfacePlayer: StateFlow<Player?> = _surfacePlayer.asStateFlow()

        init {
            player.listener = this
        }

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
                .onEach { if (!it.open) releasePlayer() }
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

        /** Home resumed with window focus; anything less releases the player at once. */
        fun setForeground(value: Boolean) {
            foreground.value = value
            if (!value) releasePlayer()
        }

        fun onCandidate(videoId: String?) {
            if (videoId == _candidate.value) return
            stopCurrent()
            _candidate.value = videoId
            if (videoId == null || videoId in done) {
                _state.value = HomePreviewState(videoId = videoId, phase = HomePreviewPhase.FINISHED, muted = muted)
                return
            }
            _state.value = HomePreviewState(videoId = videoId, muted = muted)
            job = viewModelScope.launch { run(videoId) }
        }

        /** The active card's surface came or went; nothing decodes without one. */
        fun onSurfaceAttached(
            videoId: String,
            attached: Boolean,
        ) {
            if (attached) {
                surfaceOwner.value = videoId
            } else if (surfaceOwner.value == videoId) {
                surfaceOwner.value = null
            }
        }

        /**
         * The user opened [videoId]. When it is the playing preview, its extraction goes to the watch
         * page's load and the preview stops first, so two decoders never run at once. Returns where
         * the preview was, or null when it was not previewing.
         */
        fun openAt(videoId: String): Long? {
            if (_candidate.value != videoId) return null
            val previewing = _state.value.phase.let { it == HomePreviewPhase.POSITIONED || it == HomePreviewPhase.PLAYING }
            val position = if (previewing) player.positionMs else null
            streams.handOff(videoId)
            releasePlayer()
            return position
        }

        private suspend fun run(videoId: String) {
            val slow = gate.value.slowNetwork
            val media =
                coroutineScope {
                    val resolve =
                        async {
                            delay(HomePreviewTiming.resolveDelayMs(slow))
                            streams.resolve(videoId)
                        }
                    delay(HomePreviewTiming.dwellMs(slow))
                    resolve.await()
                }
            if (media == null) {
                done += videoId
                _state.update { it.copy(phase = HomePreviewPhase.FINISHED) }
                return
            }
            surfaceOwner.first { it == videoId }
            if (PlaybackTarget.current != null) return
            val captionsOn = media.caption != null && (captionsChoice ?: media.captionsByDefault)
            _state.update {
                it.copy(
                    phase = HomePreviewPhase.POSITIONED,
                    durationMs = media.durationMs,
                    hasCaptions = media.caption != null,
                    captionsOn = captionsOn,
                    storyboard = media.storyboard,
                )
            }
            val startMs = HomePreviewTiming.resumeAtMs(stoppedAt[videoId], media.durationMs)
            player.load(media, startMs, muted, captionsOn)
            _surfacePlayer.value = player.current
            delay(HomePreviewTiming.MAX_PREVIEW_MS)
            finish(videoId)
        }

        private fun finish(videoId: String) {
            if (_candidate.value != videoId) return
            done += videoId
            stoppedAt.remove(videoId)
            job?.cancel()
            player.stop()
            _state.update { it.copy(phase = HomePreviewPhase.FINISHED, buffering = false, playing = false) }
        }

        private fun stopCurrent() {
            job?.cancel()
            job = null
            val current = _state.value
            val id = current.videoId
            if (id != null && (current.phase == HomePreviewPhase.POSITIONED || current.phase == HomePreviewPhase.PLAYING)) {
                stoppedAt[id] = player.positionMs
            }
            player.stop()
        }

        private fun releasePlayer() {
            onCandidate(null)
            player.release()
            _surfacePlayer.value = null
        }

        override fun onFirstFrame() {
            _state.update { if (it.phase == HomePreviewPhase.POSITIONED) it.copy(phase = HomePreviewPhase.PLAYING) else it }
        }

        override fun onBufferingChanged(buffering: Boolean) {
            _state.update { it.copy(buffering = buffering) }
        }

        override fun onPlayingChanged(playing: Boolean) {
            _state.update { it.copy(playing = playing) }
        }

        override fun onEnded() {
            _candidate.value?.let(::finish)
        }

        override fun onError() {
            _candidate.value?.let(::finish)
        }

        override fun onCleared() {
            player.listener = null
            job?.cancel()
            player.release()
        }

        private companion object {
            const val GATE_SUBSCRIPTION_TIMEOUT_MS = 5_000L
            const val MAX_REMEMBERED = 20
        }
    }
