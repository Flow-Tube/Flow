package io.github.aedev.flow.data.model

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.SponsorBlockAction
import org.junit.Test
import java.io.File

class SponsorBlockCategoriesTest {
    /** A category with no key of its own falls through to Sponsor's and would overwrite it. */
    @Test
    fun `every category has its own action and colour key`() {
        val preferences = File("src/main/java/io/github/aedev/flow/data/local/PlayerPreferences.kt").readText()

        SponsorBlockCategories.all.forEach { category ->
            assertThat(preferences).contains("\"sb_action_$category\"")
            assertThat(preferences).contains("\"sb_color_$category\"")
            assertThat(preferences).contains("\"$category\" -> Keys.SB_ACTION_")
            assertThat(preferences).contains("\"$category\" to Keys.SB_COLOR_")
        }
    }

    @Test
    fun `new categories start on the seek bar without skipping`() {
        assertThat(SponsorBlockCategories.defaultAction(SponsorBlockCategories.HOOK)).isEqualTo(SponsorBlockAction.IGNORE)
        assertThat(SponsorBlockCategories.defaultAction(SponsorBlockCategories.HIGHLIGHT)).isEqualTo(SponsorBlockAction.IGNORE)
    }

    @Test
    fun `a highlight cannot be skipped or muted`() {
        assertThat(SponsorBlockCategories.actionsFor(SponsorBlockCategories.HIGHLIGHT))
            .containsExactly(SponsorBlockAction.IGNORE, SponsorBlockAction.SKIP_BUTTON, SponsorBlockAction.DISABLED)
            .inOrder()
    }

    @Test
    fun `a highlight cannot be submitted but a hook can`() {
        assertThat(SponsorBlockCategories.submittable).contains(SponsorBlockCategories.HOOK)
        assertThat(SponsorBlockCategories.submittable).doesNotContain(SponsorBlockCategories.HIGHLIGHT)
    }
}
