package io.github.aedev.flow.sync.transport

/**
 * Orders stop requests against the service's own start. Android kills the app when a service started
 * with `startForegroundService` is stopped before it has called `startForeground` (#1639), and a sync
 * can end within milliseconds of starting, when the first dial is refused. A stop that arrives while
 * a start is still on its way is therefore held until `onStartCommand` has promoted the service.
 */
internal class SyncForegroundGate {
    enum class Stop { NOW, AFTER_START, NOTHING }

    private var token = 0L
    private var pendingStarts = 0
    private var foreground = false
    private var stopRequested = false

    /** Call right before `startForegroundService`; the token identifies this session's stop. */
    @Synchronized
    fun requestStart(): Long {
        pendingStarts++
        stopRequested = false
        return ++token
    }

    /** The start call itself threw, so no `onStartCommand` will follow for it. */
    @Synchronized
    fun startFailed() {
        if (pendingStarts > 0) pendingStarts--
    }

    @Synchronized
    fun requestStop(token: Long): Stop {
        if (token != this.token) return Stop.NOTHING
        return when {
            pendingStarts > 0 -> {
                stopRequested = true
                Stop.AFTER_START
            }

            foreground -> {
                foreground = false
                Stop.NOW
            }

            else -> {
                Stop.NOTHING
            }
        }
    }

    /** Call after `startForeground`; true when a stop arrived first and the service should end now. */
    @Synchronized
    fun promoted(): Boolean {
        if (pendingStarts > 0) pendingStarts--
        foreground = true
        if (!stopRequested || pendingStarts > 0) return false
        stopRequested = false
        foreground = false
        return true
    }
}
