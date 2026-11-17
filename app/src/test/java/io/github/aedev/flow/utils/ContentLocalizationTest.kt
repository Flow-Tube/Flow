package io.github.aedev.flow.utils

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.CONTENT_LANGUAGE_FOLLOW_APP
import org.junit.Test
import java.util.Locale

class ContentLocalizationTest {
    @Test
    fun `blank region falls back instead of sending an invalid gl`() {
        assertThat(normalizeYouTubeCountry("  ")).matches("[A-Z]{2}")
        assertThat(normalizeYouTubeCountry("fr")).isEqualTo("FR")
    }

    @Test
    fun `following the app asks YouTube for the language on screen (#1277)`() {
        val locale = contentYouTubeLocale("ru", CONTENT_LANGUAGE_FOLLOW_APP, "US", Locale.ENGLISH)

        assertThat(locale.hl).isEqualTo("ru")
        assertThat(locale.gl).isEqualTo("US")
    }

    @Test
    fun `a chosen content language wins over the app language`() {
        assertThat(contentYouTubeLocale("ru", "en", "RU", Locale.ENGLISH).hl).isEqualTo("en")
    }

    @Test
    fun `a system app language asks for the device language, not the one still on screen`() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("ru"))
        try {
            assertThat(contentYouTubeLocale("system", CONTENT_LANGUAGE_FOLLOW_APP, "US", Locale.ENGLISH).hl).isEqualTo("en")
        } finally {
            Locale.setDefault(previous)
        }
    }
}
