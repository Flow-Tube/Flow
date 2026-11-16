package io.github.aedev.flow.player.sponsorblock

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.SponsorBlockAction
import io.github.aedev.flow.data.model.SponsorBlockSegment
import org.junit.Test

class SponsorBlockActionPolicyTest {
    private fun segment(
        category: String,
        actionType: String = "skip",
    ) = SponsorBlockSegment(category, listOf(10f, 20f), "$category-id", actionType)

    @Test
    fun `every stored name reads back, and an unknown one falls back to skip`() {
        SponsorBlockAction.entries.forEach { assertThat(SponsorBlockAction.fromString(it.name)).isEqualTo(it) }
        assertThat(SponsorBlockAction.fromString("SOMETHING_NEW")).isEqualTo(SponsorBlockAction.SKIP)
    }

    @Test
    fun `the stored names of the original four actions never change`() {
        assertThat(SponsorBlockAction.entries.map { it.name })
            .containsAtLeast("SKIP", "MUTE", "SHOW_TOAST", "IGNORE")
    }

    @Test
    fun `only skip and a first skip once jump past the segment`() {
        assertThat(SponsorBlockActionPolicy.skipsAutomatically(SponsorBlockAction.SKIP, alreadySkippedOnce = true)).isTrue()
        assertThat(SponsorBlockActionPolicy.skipsAutomatically(SponsorBlockAction.SKIP_ONCE, alreadySkippedOnce = false)).isTrue()
        assertThat(SponsorBlockActionPolicy.skipsAutomatically(SponsorBlockAction.SKIP_ONCE, alreadySkippedOnce = true)).isFalse()
        listOf(
            SponsorBlockAction.SKIP_BUTTON,
            SponsorBlockAction.SHOW_TOAST,
            SponsorBlockAction.MUTE,
            SponsorBlockAction.IGNORE,
            SponsorBlockAction.DISABLED,
        ).forEach { assertThat(SponsorBlockActionPolicy.skipsAutomatically(it, alreadySkippedOnce = false)).isFalse() }
    }

    @Test
    fun `the skip button shows for the manual actions and after a skip once, never for seek bar only`() {
        assertThat(SponsorBlockActionPolicy.showsSkipButton(SponsorBlockAction.SKIP_BUTTON, false)).isTrue()
        assertThat(SponsorBlockActionPolicy.showsSkipButton(SponsorBlockAction.SHOW_TOAST, false)).isTrue()
        assertThat(SponsorBlockActionPolicy.showsSkipButton(SponsorBlockAction.MUTE, false)).isTrue()
        assertThat(SponsorBlockActionPolicy.showsSkipButton(SponsorBlockAction.SKIP_ONCE, false)).isFalse()
        assertThat(SponsorBlockActionPolicy.showsSkipButton(SponsorBlockAction.SKIP_ONCE, true)).isTrue()
        assertThat(SponsorBlockActionPolicy.showsSkipButton(SponsorBlockAction.SKIP, false)).isFalse()
        assertThat(SponsorBlockActionPolicy.showsSkipButton(SponsorBlockAction.IGNORE, false)).isFalse()
        assertThat(SponsorBlockActionPolicy.showsSkipButton(SponsorBlockAction.DISABLED, false)).isFalse()
    }

    @Test
    fun `a mute submission turns a skipping category into a mute, and leaves the others alone`() {
        val mute = segment("sponsor", actionType = "mute")
        assertThat(SponsorBlockActionPolicy.actionFor(mute, emptyMap())).isEqualTo(SponsorBlockAction.MUTE)
        assertThat(SponsorBlockActionPolicy.actionFor(mute, mapOf("sponsor" to SponsorBlockAction.SKIP_ONCE)))
            .isEqualTo(SponsorBlockAction.MUTE)
        assertThat(SponsorBlockActionPolicy.actionFor(mute, mapOf("sponsor" to SponsorBlockAction.SKIP_BUTTON)))
            .isEqualTo(SponsorBlockAction.SKIP_BUTTON)
        assertThat(SponsorBlockActionPolicy.actionFor(segment("filler"), emptyMap())).isEqualTo(SponsorBlockAction.IGNORE)
    }

    @Test
    fun `disabled segments are the only ones left off the seek bar`() {
        val segments = listOf(segment("sponsor"), segment("filler"), segment("intro"))
        val actions = mapOf("intro" to SponsorBlockAction.DISABLED)

        assertThat(SponsorBlockActionPolicy.visibleSegments(segments, actions).map { it.category })
            .containsExactly("sponsor", "filler")
    }
}
