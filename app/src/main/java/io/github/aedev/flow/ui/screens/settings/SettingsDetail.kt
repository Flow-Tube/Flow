package io.github.aedev.flow.ui.screens.settings

import androidx.compose.runtime.Composable
import io.github.aedev.flow.ui.components.settings.SettingsDestination
import io.github.aedev.flow.ui.components.settings.SettingsTarget
import io.github.aedev.flow.ui.screens.equalizer.EqualizerScreen
import io.github.aedev.flow.ui.screens.settings.about.AboutScreen
import io.github.aedev.flow.ui.screens.settings.appearance.AppearanceScreen
import io.github.aedev.flow.ui.screens.settings.appearance.DateTimeScreen
import io.github.aedev.flow.ui.screens.settings.appearance.FontScreen
import io.github.aedev.flow.ui.screens.settings.appearance.NavigationBarScreen
import io.github.aedev.flow.ui.screens.settings.appearance.player.PlayerAppearanceScreen
import io.github.aedev.flow.ui.screens.settings.appearance.theme.CustomThemeEditorScreen
import io.github.aedev.flow.ui.screens.settings.appearance.theme.CustomThemesScreen
import io.github.aedev.flow.ui.screens.settings.appearance.theme.ThemeScreen
import io.github.aedev.flow.ui.screens.settings.backup.BackupScreen
import io.github.aedev.flow.ui.screens.settings.content.ContentSettingsScreen
import io.github.aedev.flow.ui.screens.settings.content.LibrarySettingsScreen
import io.github.aedev.flow.ui.screens.settings.content.MusicHomeSettingsScreen
import io.github.aedev.flow.ui.screens.settings.diagnostics.DiagnosticsScreen
import io.github.aedev.flow.ui.screens.settings.downloads.DownloadSettingsScreen
import io.github.aedev.flow.ui.screens.settings.history.HistorySettingsScreen
import io.github.aedev.flow.ui.screens.settings.integrations.IntegrationsScreen
import io.github.aedev.flow.ui.screens.settings.localmedia.LocalMediaSettingsScreen
import io.github.aedev.flow.ui.screens.settings.network.NetworkSettingsScreen
import io.github.aedev.flow.ui.screens.settings.notifications.NotificationSettingsScreen
import io.github.aedev.flow.ui.screens.settings.playback.BufferSettingsScreen
import io.github.aedev.flow.ui.screens.settings.playback.PlaybackSettingsScreen
import io.github.aedev.flow.ui.screens.settings.profiles.ProfileEditScreen
import io.github.aedev.flow.ui.screens.settings.profiles.ProfilesScreen
import io.github.aedev.flow.ui.screens.settings.quality.QualitySettingsScreen
import io.github.aedev.flow.ui.screens.settings.region.LanguageRegionScreen
import io.github.aedev.flow.ui.screens.settings.scrobbling.ScrobblingScreen
import io.github.aedev.flow.ui.screens.settings.security.SecurityScreen
import io.github.aedev.flow.ui.screens.settings.taste.DiscoverChannelsScreen
import io.github.aedev.flow.ui.screens.settings.taste.FavouriteArtistsScreen
import io.github.aedev.flow.ui.screens.settings.taste.HiddenContentScreen
import io.github.aedev.flow.ui.screens.settings.taste.TasteScreen
import io.github.aedev.flow.ui.screens.settings.topics.TopicPreferencesScreen
import io.github.aedev.flow.ui.screens.settings.wellbeing.WellbeingScreen
import io.github.aedev.flow.ui.screens.settings.widgets.WidgetsSettingsScreen
import io.github.aedev.flow.ui.screens.sync.SyncScreen

/**
 * The page a [SettingsTarget] opens. [onBack] is null for the root page beside the settings list,
 * where there is nothing to go back to; [onNavigate] opens a sub-page over this one.
 */
@Composable
internal fun SettingsDetail(
    target: SettingsTarget,
    onBack: (() -> Unit)?,
    onNavigate: (SettingsTarget) -> Unit,
    onOpenRecap: () -> Unit,
    options: SettingsHostOptions = SettingsHostOptions(),
) {
    options.pages[target.destination]?.let { page ->
        page(onBack)
        return
    }
    when (target.destination) {
        SettingsDestination.HOME,
        SettingsDestination.APPEARANCE,
        -> {
            AppearanceScreen(onBack = onBack, highlight = target.highlight, onNavigate = onNavigate)
        }

        SettingsDestination.TASTE -> {
            TasteScreen(onBack = onBack, highlight = target.highlight, onNavigate = onNavigate, onOpenRecap = onOpenRecap)
        }

        SettingsDestination.HIDDEN_CONTENT -> {
            HiddenContentScreen(onBack = onBack, highlight = target.highlight)
        }

        SettingsDestination.FAVOURITE_ARTISTS -> {
            FavouriteArtistsScreen(onBack = onBack, highlight = target.highlight)
        }

        SettingsDestination.DISCOVER_CHANNELS -> {
            DiscoverChannelsScreen(onBack = onBack, highlight = target.highlight)
        }

        SettingsDestination.THEME -> {
            ThemeScreen(onBack = onBack, highlight = target.highlight, onNavigate = onNavigate)
        }

        SettingsDestination.FONT -> {
            FontScreen(onBack = onBack, highlight = target.highlight)
        }

        SettingsDestination.CUSTOM_THEME -> {
            CustomThemesScreen(onBack = onBack, highlight = target.highlight, onNavigate = onNavigate)
        }

        SettingsDestination.CUSTOM_THEME_EDIT -> {
            CustomThemeEditorScreen(
                themeId = target.tab,
                onBack = onBack,
                highlight = target.highlight,
            )
        }

        SettingsDestination.NAVIGATION_BAR -> {
            NavigationBarScreen(onBack = onBack, highlight = target.highlight)
        }

        SettingsDestination.CONTENT -> {
            ContentSettingsScreen(onBack = onBack, highlight = target.highlight, onNavigate = onNavigate)
        }

        SettingsDestination.MUSIC_HOME -> {
            MusicHomeSettingsScreen(onBack = onBack, highlight = target.highlight)
        }

        SettingsDestination.LIBRARY -> {
            LibrarySettingsScreen(onBack = onBack, highlight = target.highlight)
        }

        SettingsDestination.DATE_TIME -> {
            DateTimeScreen(onBack = onBack, highlight = target.highlight)
        }

        SettingsDestination.PLAYER_APPEARANCE -> {
            PlayerAppearanceScreen(onBack = onBack, highlight = target.highlight)
        }

        SettingsDestination.LANGUAGE_REGION -> {
            LanguageRegionScreen(onBack = onBack, highlight = target.highlight)
        }

        SettingsDestination.PLAYBACK -> {
            PlaybackSettingsScreen(onBack = onBack, highlight = target.highlight, onNavigate = onNavigate)
        }

        SettingsDestination.BUFFER -> {
            BufferSettingsScreen(onBack = onBack, highlight = target.highlight, onNavigate = onNavigate)
        }

        SettingsDestination.EQUALIZER -> {
            EqualizerScreen(onBack = onBack, inSettings = true, highlight = target.highlight)
        }

        SettingsDestination.QUALITY -> {
            QualitySettingsScreen(onBack = onBack, highlight = target.highlight, tab = target.tab)
        }

        SettingsDestination.TOPICS -> {
            TopicPreferencesScreen(onBack = onBack, highlight = target.highlight, tab = target.tab)
        }

        SettingsDestination.INTEGRATIONS -> {
            IntegrationsScreen(onBack = onBack, highlight = target.highlight, onNavigate = onNavigate)
        }

        SettingsDestination.SCROBBLING -> {
            ScrobblingScreen(onBack = onBack, highlight = target.highlight)
        }

        SettingsDestination.BACKUP -> {
            BackupScreen(onBack = onBack, highlight = target.highlight, tab = target.tab)
        }

        SettingsDestination.SYNC -> {
            SyncScreen(onBack = onBack)
        }

        SettingsDestination.PROFILES -> {
            ProfilesScreen(onBack = onBack, highlight = target.highlight, onNavigate = onNavigate)
        }

        SettingsDestination.PROFILE_EDIT -> {
            ProfileEditScreen(profileId = target.tab, onBack = onBack, highlight = target.highlight)
        }

        SettingsDestination.HISTORY -> {
            HistorySettingsScreen(onBack = onBack, highlight = target.highlight)
        }

        SettingsDestination.DOWNLOADS -> {
            DownloadSettingsScreen(onBack = onBack, highlight = target.highlight, onNavigate = onNavigate)
        }

        SettingsDestination.LOCAL_MEDIA -> {
            LocalMediaSettingsScreen(onBack = onBack, highlight = target.highlight)
        }

        SettingsDestination.NOTIFICATIONS -> {
            NotificationSettingsScreen(onBack = onBack, highlight = target.highlight)
        }

        SettingsDestination.NETWORK -> {
            NetworkSettingsScreen(onBack = onBack, highlight = target.highlight)
        }

        SettingsDestination.WELLBEING -> {
            WellbeingScreen(onBack = onBack, highlight = target.highlight)
        }

        SettingsDestination.SECURITY -> {
            SecurityScreen(onBack = onBack, highlight = target.highlight)
        }

        SettingsDestination.WIDGETS -> {
            WidgetsSettingsScreen(onBack = onBack, highlight = target.highlight)
        }

        SettingsDestination.ABOUT -> {
            AboutScreen(onBack = onBack, highlight = target.highlight, onNavigate = onNavigate)
        }

        SettingsDestination.REMOTE_GUIDE -> {
            Unit
        }

        SettingsDestination.DIAGNOSTICS -> {
            DiagnosticsScreen(onBack = onBack, highlight = target.highlight)
        }
    }
}
