package io.github.aedev.flow.utils

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.CONTENT_LANGUAGE_FOLLOW_APP
import io.github.aedev.flow.innertube.models.YouTubeLocale
import org.junit.Test
import java.util.Locale

/** The hl and gl every InnerTube request carries after a language or region change, as values. */
class YouTubeLocaleValuesTest {
    private fun locale(
        appLanguage: String,
        contentLanguage: String = CONTENT_LANGUAGE_FOLLOW_APP,
        region: String = "US",
    ) = contentYouTubeLocale(appLanguage, contentLanguage, region, Locale.ENGLISH)

    @Test
    fun `app languages become the hl YouTube accepts`() {
        assertThat(locale("en")).isEqualTo(YouTubeLocale(gl = "US", hl = "en"))
        assertThat(locale("de-DE")).isEqualTo(YouTubeLocale(gl = "US", hl = "de"))
        assertThat(locale("pt-BR")).isEqualTo(YouTubeLocale(gl = "US", hl = "pt-BR"))
        assertThat(locale("pt-PT")).isEqualTo(YouTubeLocale(gl = "US", hl = "pt"))
        assertThat(locale("zh-TW")).isEqualTo(YouTubeLocale(gl = "US", hl = "zh-TW"))
        assertThat(locale("zh-Hant")).isEqualTo(YouTubeLocale(gl = "US", hl = "zh-TW"))
        assertThat(locale("zh-HK")).isEqualTo(YouTubeLocale(gl = "US", hl = "zh-HK"))
        assertThat(locale("zh-CN")).isEqualTo(YouTubeLocale(gl = "US", hl = "zh-CN"))
        assertThat(locale("in")).isEqualTo(YouTubeLocale(gl = "US", hl = "id"))
        assertThat(locale("kab")).isEqualTo(YouTubeLocale(gl = "US", hl = "en"))
        assertThat(locale("es_419")).isEqualTo(YouTubeLocale(gl = "US", hl = "es"))
    }

    @Test
    fun `a chosen content language and region are sent as chosen`() {
        assertThat(locale("en", contentLanguage = "ja", region = "jp")).isEqualTo(YouTubeLocale(gl = "JP", hl = "ja"))
        // A language picked from YouTube's own list keeps its regional variant; it used to be cut to "en".
        assertThat(locale("en", contentLanguage = "en-GB", region = "GB")).isEqualTo(YouTubeLocale(gl = "GB", hl = "en-GB"))
        assertThat(locale("en", contentLanguage = "pt-PT", region = "PT")).isEqualTo(YouTubeLocale(gl = "PT", hl = "pt-PT"))
        assertThat(locale("en", contentLanguage = "es-419", region = "MX")).isEqualTo(YouTubeLocale(gl = "MX", hl = "es-419"))
    }

    @Test
    fun `an invalid region falls back to the device country, then the US`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("fr-CA"))
            assertThat(locale("en", region = "XYZ").gl).isEqualTo("CA")
            Locale.setDefault(Locale.forLanguageTag("fr"))
            assertThat(locale("en", region = "").gl).isEqualTo("US")
        } finally {
            Locale.setDefault(previous)
        }
    }
}
