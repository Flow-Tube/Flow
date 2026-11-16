package io.github.aedev.flow.ui.screens.player.state

import io.github.aedev.flow.ui.components.videoplayer.settings.PlayerSettingsPage

/**
 * The one sheet, panel or dialog the video player has raised over the stage. The player can only
 * show one at a time, so opening any of them closes whatever stood before it rather than stacking
 * two surfaces over the video.
 */
internal sealed interface PlayerSheet {
    data object None : PlayerSheet

    /**
     * The player settings sheet. [page] is the page it opens on, so the control bar's quality,
     * speed and subtitle actions land directly on their own page instead of the main list.
     */
    data class Settings(
        val page: PlayerSettingsPage = PlayerSettingsPage.Main,
    ) : PlayerSheet

    data object Download : PlayerSheet

    data object SleepTimer : PlayerSheet

    data object Dlna : PlayerSheet

    /** [fullscreen] picks the landscape side panel over the bottom sheet. */
    data class Comments(
        val fullscreen: Boolean = false,
    ) : PlayerSheet

    data object Description : PlayerSheet

    data object Chapters : PlayerSheet

    data object Transcript : PlayerSheet

    data object Queue : PlayerSheet

    /** [fullscreen] picks the landscape side panel over the bottom sheet. */
    data class LiveChat(
        val fullscreen: Boolean = false,
    ) : PlayerSheet

    data object SbSubmit : PlayerSheet

    /** The video note editor, raised from the settings sheet so fullscreen can write one too. */
    data object Note : PlayerSheet

    /** The row of related videos a swipe up raises over landscape fullscreen. */
    data object MoreVideos : PlayerSheet
}
