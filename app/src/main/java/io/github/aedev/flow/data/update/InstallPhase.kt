package io.github.aedev.flow.data.update

/** Where the installer session for a downloaded update stands. */
sealed interface InstallPhase {
    data object Idle : InstallPhase

    /** Android is showing its own "update this app?" confirmation. */
    data object Confirming : InstallPhase

    /** [progress] runs 0..1, or is null before the session reports any. */
    data class Installing(
        val progress: Float?,
    ) : InstallPhase

    data object Failed : InstallPhase
}

sealed interface InstallEvent {
    data object Started : InstallEvent

    data object NeedsConfirmation : InstallEvent

    data class Progress(
        val progress: Float,
    ) : InstallEvent

    /** The user cancelled Android's confirmation, or there was nothing to install. */
    data object Aborted : InstallEvent

    data object Rejected : InstallEvent

    /** Flow came back to the foreground; a busy session that no longer exists never sent its result. */
    data class Resumed(
        val sessionExists: Boolean,
    ) : InstallEvent
}

fun InstallPhase.on(event: InstallEvent): InstallPhase =
    when (event) {
        InstallEvent.Started -> {
            InstallPhase.Installing(null)
        }

        InstallEvent.NeedsConfirmation -> {
            InstallPhase.Confirming
        }

        is InstallEvent.Progress -> {
            if (isBusy) InstallPhase.Installing(event.progress) else this
        }

        InstallEvent.Aborted -> {
            InstallPhase.Idle
        }

        InstallEvent.Rejected -> {
            InstallPhase.Failed
        }

        is InstallEvent.Resumed -> {
            if (isBusy && !event.sessionExists) InstallPhase.Idle else this
        }
    }

private val InstallPhase.isBusy: Boolean
    get() = this is InstallPhase.Confirming || this is InstallPhase.Installing
