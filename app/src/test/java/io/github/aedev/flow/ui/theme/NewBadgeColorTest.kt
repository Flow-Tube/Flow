package io.github.aedev.flow.ui.theme

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class NewBadgeColorTest {
    private val palettes =
        ThemeCatalog.palettes
            .map { it.mode }
            .filterNot { it == ThemeMode.MATERIAL_YOU }
            .map { it to FlowPalettes.forMode(it) }

    @Test
    fun `the dot reads on every palette surface`() {
        palettes.forEach { (mode, pair) ->
            listOf(pair.light, pair.dark).forEach { seed ->
                listOf(seed.background, seed.surface).forEach { surface ->
                    assertWithMessage("$mode $surface")
                        .that(contrastRatio(newBadgeColor(surface), surface))
                        .isAtLeast(3f)
                }
            }
        }
    }

    @Test
    fun `the dot does not follow the theme accent`() {
        val darkDots = palettes.map { (_, pair) -> newBadgeColor(pair.dark.surface) }.toSet()
        val lightDots = palettes.map { (_, pair) -> newBadgeColor(pair.light.surface) }.toSet()

        assertThat(darkDots).hasSize(1)
        assertThat(lightDots).hasSize(1)
    }
}
