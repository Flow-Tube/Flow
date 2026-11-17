package io.github.aedev.flow.ui.screens.settings.region

import android.content.Context
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.CONTENT_LANGUAGE_FOLLOW_APP
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.translation.TranslationServer
import io.github.aedev.flow.data.translation.TranslationServerType
import io.github.aedev.flow.data.translation.TranslationSettings
import io.github.aedev.flow.innertube.models.YouTubeContentLanguages
import io.github.aedev.flow.ui.screens.settings.SettingsViewModel
import io.github.aedev.flow.utils.AppLanguageManager
import java.util.Locale
import javax.inject.Inject

/** A language as a picker lists it: its own name first, then its name in the UI language. */
data class LanguageOption(
    val tag: String,
    val nativeName: String,
    val localizedName: String?,
)

@HiltViewModel
class LanguageRegionViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val preferences: PlayerPreferences,
    ) : SettingsViewModel() {
        val appLanguage = preferences.appLanguage.asState(AppLanguageManager.SYSTEM_DEFAULT)
        val contentLanguage = preferences.contentLanguage.asState(CONTENT_LANGUAGE_FOLLOW_APP)
        val contentCountry = preferences.trendingRegion.asState(DEFAULT_COUNTRY)
        val originalTitles = preferences.originalTitles.asState(false)
        val translation = preferences.translationSettings.asState(TranslationSettings())

        /** The languages YouTube localizes content into, i.e. every value its `hl` accepts. */
        val contentLanguages: List<LanguageOption> by lazy {
            val displayLocale = Locale.getDefault()
            YouTubeContentLanguages
                .map { tag ->
                    val locale = Locale.forLanguageTag(tag)
                    val native = locale.getDisplayName(locale)
                    LanguageOption(
                        tag = tag,
                        nativeName = native,
                        localizedName = locale.getDisplayName(displayLocale).takeIf { it != native },
                    )
                }.sortedBy { it.nativeName.lowercase(displayLocale) }
        }

        /**
         * Saves the app language. Below Android 13 the screen recreates the activity to apply it;
         * from 13 the system does that itself.
         */
        fun setAppLanguage(tag: String) =
            write {
                preferences.setAppLanguage(tag)
                AppLanguageManager.applyLanguageTag(context, tag)
            }

        fun setContentLanguage(tag: String) = write { preferences.setContentLanguage(tag) }

        fun setContentCountry(code: String) = write { preferences.setTrendingRegion(code) }

        fun setOriginalTitles(enabled: Boolean) = write { preferences.setOriginalTitles(enabled) }

        fun setTranslateWithServer(useServer: Boolean) = write { preferences.setTranslationUseServer(useServer) }

        /** Saves the server and switches Translate to it; an address Flow won't send text to is refused. */
        fun saveTranslationServer(
            type: TranslationServerType,
            url: String,
            apiKey: String,
            onResult: (Boolean) -> Unit,
        ) {
            val normalized = TranslationServer.normalizeUrl(url) ?: return onResult(false)
            write { preferences.setTranslationServer(type, normalized, apiKey.takeIf { type == TranslationServerType.LIBRETRANSLATE }) }
            onResult(true)
        }

        fun setTranslationTarget(tag: String) = write { preferences.setTranslationTarget(tag) }

        private companion object {
            const val DEFAULT_COUNTRY = "US"
        }
    }
