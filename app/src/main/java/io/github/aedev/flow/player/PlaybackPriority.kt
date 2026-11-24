package io.github.aedev.flow.player

import androidx.media3.common.PriorityTaskManager
import androidx.media3.common.util.UnstableApi

/**
 * The app's one Media3 [PriorityTaskManager]. Every player registers on it while it loads, and a
 * download waits behind it between blocks, so what is playing never shares the connection with a
 * download (YouTube's offline transfers sit at a priority below playback the same way).
 */
@UnstableApi
object PlaybackPriority {
    val tasks = PriorityTaskManager()
}
