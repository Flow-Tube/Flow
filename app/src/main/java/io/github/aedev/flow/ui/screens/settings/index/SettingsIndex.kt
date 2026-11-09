package io.github.aedev.flow.ui.screens.settings.index

import io.github.aedev.flow.ui.components.settings.SettingEntry
import io.github.aedev.flow.ui.screens.equalizer.EqualizerIndex

/** Every option in Settings, in the order the pages show them. */
internal object SettingsIndex {
    val all: List<SettingEntry> by lazy {
        HomeIndex.all +
            TasteIndex.all +
            DestinationIndex.all +
            AppearanceIndex.all +
            ThemeIndex.all +
            CustomThemeIndex.all +
            FontIndex.all +
            NavigationBarIndex.all +
            DateTimeIndex.all +
            PlayerAppearanceIndex.all +
            LanguageRegionIndex.all +
            PlaybackIndex.all +
            EqualizerIndex.all +
            BufferIndex.all +
            QualityIndex.all +
            ContentIndex.all +
            MusicHomeIndex.all +
            LibrarySectionsIndex.all +
            TopicsIndex.all +
            IntegrationsIndex.all +
            ScrobblingIndex.all +
            ProfilesIndex.all +
            BackupIndex.all +
            DownloadsIndex.all +
            LocalMediaIndex.all +
            NotificationsIndex.all +
            NetworkIndex.all +
            HistoryIndex.all +
            WellbeingIndex.all +
            SecurityIndex.all +
            WidgetsIndex.all +
            DiagnosticsIndex.all +
            AboutIndex.all
    }
}
