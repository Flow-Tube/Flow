package io.github.aedev.flow.ui.components.layout.navigation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.aedev.flow.ui.components.settings.SettingsTarget
import io.github.aedev.flow.utils.YouTubeLink

/**
 * The app-wide way to open the page behind a piece of media: its channel, artist, album or playlist,
 * the equalizer the players' audio settings link to, a settings page a menu links to, and whatever a
 * pasted YouTube link points at.
 *
 * Cards and quick action sheets call this directly instead of taking a callback from every screen
 * that shows them, so one implementation decides how the players get out of the way and how a page
 * that is already open is not opened again.
 */
@Stable
interface MediaNavigator {
    fun openChannel(channelId: String)

    fun openArtist(artistId: String)

    fun openAlbum(albumId: String)

    fun openMusicPlaylist(playlistId: String)

    fun openEqualizer()

    /** Opens a settings page, such as where downloads are saved; a host with no settings ignores it. */
    fun openSettings(target: SettingsTarget) = Unit

    /** Opens what [link] points at. False when Flow has no page for it, so the caller can say so. */
    fun openLink(link: YouTubeLink): Boolean
}

private object NoOpMediaNavigator : MediaNavigator {
    override fun openChannel(channelId: String) = Unit

    override fun openArtist(artistId: String) = Unit

    override fun openAlbum(albumId: String) = Unit

    override fun openMusicPlaylist(playlistId: String) = Unit

    override fun openEqualizer() = Unit

    override fun openLink(link: YouTubeLink) = false
}

/** Static because the shell provides one navigator for its whole lifetime. Previews and tests get a no-op. */
val LocalMediaNavigator = staticCompositionLocalOf<MediaNavigator> { NoOpMediaNavigator }
