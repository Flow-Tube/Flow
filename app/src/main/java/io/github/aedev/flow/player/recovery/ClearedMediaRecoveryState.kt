package io.github.aedev.flow.player.recovery

internal data class ClearedMediaRecoverySnapshot(
    val videoId: String,
    val positionMs: Long,
    val playWhenReady: Boolean,
    val localFilePath: String?,
    /** False when only an explicit action (Play, Retry) may reload it, as after a decoder failure. */
    val restoresOnForeground: Boolean = true,
)

internal class ClearedMediaRecoveryState {
    private var pendingSnapshot: ClearedMediaRecoverySnapshot? = null

    fun capture(
        videoId: String?,
        positionMs: Long,
        playWhenReady: Boolean,
        localFilePath: String?,
        restoresOnForeground: Boolean = true,
    ) {
        if (videoId == null) return
        pendingSnapshot =
            ClearedMediaRecoverySnapshot(
                videoId = videoId,
                positionMs = positionMs.coerceAtLeast(0L),
                playWhenReady = playWhenReady,
                localFilePath = localFilePath,
                restoresOnForeground = restoresOnForeground,
            )
    }

    fun pendingFor(videoId: String?): ClearedMediaRecoverySnapshot? = pendingSnapshot?.takeIf { it.videoId == videoId }

    fun complete(snapshot: ClearedMediaRecoverySnapshot) {
        if (pendingSnapshot === snapshot) {
            pendingSnapshot = null
        }
    }

    /**
     * Where a reload should start: the saved position while the player is still empty for [videoId],
     * consuming it, otherwise [currentPositionMs]. An emptied player reports 0.
     */
    fun takeReloadPosition(
        videoId: String?,
        playerEmpty: Boolean,
        currentPositionMs: Long,
    ): Long {
        val snapshot = if (playerEmpty) pendingFor(videoId) else null
        snapshot ?: return currentPositionMs
        complete(snapshot)
        return snapshot.positionMs
    }

    fun clear() {
        pendingSnapshot = null
    }
}
