package io.github.aedev.flow.data.local

import android.content.Context
import androidx.compose.ui.text.font.FontFamily
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.profile.StoreScope
import io.github.aedev.flow.ui.theme.AppFont
import io.github.aedev.flow.ui.theme.appFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** The font as Settings shows it. [customName] is set only while the custom font file is present. */
data class AppFontSelection(
    val font: AppFont = AppFont.SYSTEM,
    val customName: String? = null,
)

private val Context.appFontDataStore: DataStore<Preferences> by safePreferencesDataStore(
    name = "app_font",
    scope = StoreScope.PROFILE_SETTINGS,
)

/** The app font choice. The custom font file itself lives in [CustomFontStore] and is not backed up. */
class AppFontPreferences
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val appContext = context.applicationContext
        val customFonts = CustomFontStore(appContext)

        val font: Flow<AppFont> = appContext.appFontDataStore.data.map { AppFont.fromStorage(it[FONT]) }

        /** A custom choice whose file is gone, such as after restoring a backup, reads as the system font. */
        val selection: Flow<AppFontSelection> =
            appContext.appFontDataStore.data
                .map { prefs ->
                    val hasFile = customFonts.file() != null
                    val stored = AppFont.fromStorage(prefs[FONT])
                    AppFontSelection(
                        font = if (stored == AppFont.CUSTOM && !hasFile) AppFont.SYSTEM else stored,
                        customName = prefs[CUSTOM_NAME].takeIf { hasFile },
                    )
                }.flowOn(Dispatchers.IO)

        /** What the app draws with, resolved off the main thread. A missing or broken custom file is the system font. */
        val fontFamily: Flow<FontFamily> =
            appContext.appFontDataStore.data
                .map { AppFont.fromStorage(it[FONT]) to it[CUSTOM_IMPORTED_AT] }
                .distinctUntilChanged()
                .map { (font, _) -> appFontFamily(font, customFonts.file(), customFonts::loadFamily) }
                .flowOn(Dispatchers.IO)

        suspend fun setFont(font: AppFont) {
            appContext.appFontDataStore.edit { it[FONT] = font.storageId }
        }

        suspend fun setCustomFont(displayName: String) {
            appContext.appFontDataStore.edit {
                it[FONT] = AppFont.CUSTOM.storageId
                it[CUSTOM_NAME] = displayName
                it[CUSTOM_IMPORTED_AT] = System.currentTimeMillis()
            }
        }

        suspend fun getSettingsBackup(): SettingsBackup = SettingsBackup(strings = mapOf(FONT.name to font.first().storageId))

        suspend fun restoreSettings(backup: SettingsBackup) {
            val restored = backup.strings[FONT.name] ?: return
            setFont(AppFont.fromStorage(restored))
        }

        companion object {
            private val FONT = stringPreferencesKey("app_font")
            private val CUSTOM_NAME = stringPreferencesKey("app_font_custom_name")
            private val CUSTOM_IMPORTED_AT = longPreferencesKey("app_font_custom_imported_at")

            /** The settings [getSettingsBackup] carries. */
            internal val SETTING_NAMES: Set<String> = setOf(FONT.name)
        }
    }
