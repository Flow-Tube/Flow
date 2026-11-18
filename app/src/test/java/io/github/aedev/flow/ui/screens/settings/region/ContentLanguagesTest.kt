package io.github.aedev.flow.ui.screens.settings.region

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.PlayerPreferences
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * The content languages (and translation targets) the Language and region screen offers: YouTube's
 * own list of `hl` values. NewPipe 0.26.5's list held only "en-GB", so the pickers used to offer
 * English (United Kingdom) alone beside "follow the app".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContentLanguagesTest {
    private lateinit var defaultLocale: Locale

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        defaultLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        Locale.setDefault(defaultLocale)
        Dispatchers.resetMain()
    }

    @Test
    fun `the pickers list every YouTube content language by its own name`() {
        val viewModel = LanguageRegionViewModel(mockk<Context>(relaxed = true), mockk<PlayerPreferences>(relaxed = true))
        val languages = viewModel.contentLanguages

        assertThat(languages).hasSize(83)
        assertThat(languages.map { it.tag }).containsAtLeast("en-GB", "pt-PT", "pt", "es-419", "iw", "zh-TW", "zh-HK", "sr-Latn", "fil")
        assertThat(languages).contains(LanguageOption(tag = "en-GB", nativeName = "English (United Kingdom)", localizedName = null))
        assertThat(languages).contains(LanguageOption(tag = "de", nativeName = "Deutsch", localizedName = "German"))
        assertThat(languages.map { it.nativeName }).containsNoDuplicates()
        assertThat(languages.map { it.nativeName.lowercase(Locale.US) }).isInOrder()
    }
}
