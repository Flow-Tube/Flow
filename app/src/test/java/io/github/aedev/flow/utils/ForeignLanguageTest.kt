package io.github.aedev.flow.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ForeignLanguageTest {
    private val viewer = setOf("en", "he")

    @Test
    fun `a confident guess in another language is foreign`() {
        assertThat(isForeignLanguage(listOf("ja" to 0.92f), viewer)).isTrue()
    }

    @Test
    fun `the viewer's own language is not foreign, whatever the region`() {
        assertThat(isForeignLanguage(listOf("en-GB" to 0.9f), viewer)).isFalse()
    }

    @Test
    fun `an unsure guess is not foreign`() {
        assertThat(isForeignLanguage(listOf("ja" to 0.3f, "zh" to 0.2f), viewer)).isFalse()
    }

    @Test
    fun `the most likely guess decides`() {
        assertThat(isForeignLanguage(listOf("en" to 0.35f, "es" to 0.6f), viewer)).isTrue()
        assertThat(isForeignLanguage(listOf("es" to 0.35f, "en" to 0.6f), viewer)).isFalse()
    }

    @Test
    fun `no guess or an undetermined one is not foreign`() {
        assertThat(isForeignLanguage(emptyList(), viewer)).isFalse()
        assertThat(isForeignLanguage(listOf("und" to 0.99f), viewer)).isFalse()
    }

    @Test
    fun `old Java codes match their current spelling`() {
        assertThat(isForeignLanguage(listOf("iw" to 0.9f), viewer)).isFalse()
        assertThat(baseLanguage("in")).isEqualTo("id")
        assertThat(baseLanguage("pt_BR")).isEqualTo("pt")
    }
}
