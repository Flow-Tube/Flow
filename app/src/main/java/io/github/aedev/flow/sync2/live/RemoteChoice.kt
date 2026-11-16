package io.github.aedev.flow.sync2.live

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** How often a chosen device is checked for having gone quiet, between live messages. */
const val QUIET_CHECK_MS = 10_000L

/**
 * The device the user chose in "Play on" to drive from here, by moving playback to it or by
 * controlling it. Another device playing on its own is never chosen for them, and only playback
 * coming back here ends the choice, besides the device going away.
 */
@Singleton
class RemoteChoice
    @Inject
    constructor() {
        private val chosen = MutableStateFlow<String?>(null)

        val did: StateFlow<String?> = chosen.asStateFlow()

        fun choose(did: String) {
            chosen.value = did
        }

        fun clear() {
            chosen.value = null
        }

        /**
         * Forgets the choice when the device can no longer be reached, checked whenever [changes] emits
         * and, while a device is chosen, every [QUIET_CHECK_MS]. Runs until cancelled.
         */
        suspend fun watch(
            changes: Flow<*>,
            reachable: (String) -> Boolean,
        ) = coroutineScope {
            launch {
                chosen.collectLatest { did ->
                    while (did != null) {
                        delay(QUIET_CHECK_MS)
                        if (!reachable(did)) clear()
                    }
                }
            }
            combine(chosen, changes) { did, _ -> did }.collect { did -> if (did != null && !reachable(did)) clear() }
        }
    }
