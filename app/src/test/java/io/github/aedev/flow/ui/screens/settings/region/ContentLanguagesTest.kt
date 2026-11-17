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
 * The content languages (and translation targets) the Language and region screen offers. They
 * come from NewPipe's list of YouTube localizations, which in 0.26.5 holds a single entry, so the
 * pickers offer English (United Kingdom) beside "follow the app". Pinned so the native list that
 * replaces it is a decision, not an accident.
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
    fun `the pickers list each YouTube content language by its own name`() {
        val viewModel = LanguageRegionViewModel(mockk<Context>(relaxed = true), mockk<PlayerPreferences>(relaxed = true))

        assertThat(viewModel.contentLanguages)
            .containsExactly(LanguageOption(tag = "en-GB", nativeName = "English (United Kingdom)", localizedName = null))
    }
}
