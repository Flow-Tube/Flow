package io.github.aedev.flow.player.crossfade

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** Crossfade turned off from the player's menu until the app restarts; the saved settings stay as they are. */
@Singleton
class CrossfadeSessionPause
    @Inject
    constructor() {
        private val _paused = MutableStateFlow(false)
        val paused: StateFlow<Boolean> = _paused.asStateFlow()

        fun toggle() = _paused.update { !it }
    }
