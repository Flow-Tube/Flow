package io.github.aedev.flow.ui.components.videoplayer.overlay

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.SponsorBlockAction
import io.github.aedev.flow.data.local.SponsorBlockSkipButtonStyle
import io.github.aedev.flow.data.model.SponsorBlockSegment
import org.junit.Test

class SponsorBlockSkipPolicyTest {
    private val outro =
        SponsorBlockSegment(
            category = "outro",
            segment = listOf(110f, 125f),
            uuid = "outro-id",
            actionType = "skip",
        )

    @Test
    fun `a skip button style that is missing or unknown reads as full`() {
        assertThat(SponsorBlockSkipButtonStyle.fromStored(null)).isEqualTo(SponsorBlockSkipButtonStyle.FULL)
        assertThat(SponsorBlockSkipButtonStyle.fromStored("LARGE")).isEqualTo(SponsorBlockSkipButtonStyle.FULL)
        assertThat(SponsorBlockSkipButtonStyle.fromStored("ICON_ONLY")).isEqualTo(SponsorBlockSkipButtonStyle.ICON_ONLY)
    }

    @Test
    fun `manual outro is visible while playback is active`() {
        val active =
            findActiveManualSponsorSegment(
                sponsorSegments = listOf(outro),
                currentPositionMs = 120_000L,
                skippedUuids = emptySet(),
                categoryActions = mapOf("outro" to SponsorBlockAction.SHOW_TOAST),
                playbackEnded = false,
            )

        assertThat(active).isEqualTo(outro)
    }

    @Test
    fun `manual outro is hidden after playback ends`() {
        val active =
            findActiveManualSponsorSegment(
                sponsorSegments = listOf(outro),
                currentPositionMs = 120_000L,
                skippedUuids = emptySet(),
                categoryActions = mapOf("outro" to SponsorBlockAction.SHOW_TOAST),
                playbackEnded = true,
            )

        assertThat(active).isNull()
    }

    @Test
    fun `seek bar only shows no button, skip once shows it after its skip`() {
        val ignored =
            findActiveManualSponsorSegment(
                sponsorSegments = listOf(outro),
                currentPositionMs = 120_000L,
                skippedUuids = emptySet(),
                categoryActions = mapOf("outro" to SponsorBlockAction.IGNORE),
                playbackEnded = false,
            )
        val once = { skippedOnce: Set<String> ->
            findActiveManualSponsorSegment(
                sponsorSegments = listOf(outro),
                currentPositionMs = 120_000L,
                skippedUuids = emptySet(),
                categoryActions = mapOf("outro" to SponsorBlockAction.SKIP_ONCE),
                playbackEnded = false,
                skippedOnceUuids = skippedOnce,
            )
        }

        assertThat(ignored).isNull()
        assertThat(once(emptySet())).isNull()
        assertThat(once(setOf(outro.uuid))).isEqualTo(outro)
    }

    private val highlight =
        SponsorBlockSegment(
            category = "poi_highlight",
            segment = listOf(150f, 150f),
            uuid = "highlight-id",
            actionType = "poi",
        )

    private fun activeHighlight(
        positionMs: Long,
        action: SponsorBlockAction = SponsorBlockAction.SKIP_BUTTON,
        segment: SponsorBlockSegment = highlight,
        skipped: Set<String> = emptySet(),
    ) = findActiveManualSponsorSegment(
        sponsorSegments = listOf(segment),
        currentPositionMs = positionMs,
        skippedUuids = skipped,
        categoryActions = mapOf("poi_highlight" to action),
        playbackEnded = false,
    )

    @Test
    fun `the jump to a highlight is offered in the first seconds only`() {
        assertThat(activeHighlight(positionMs = 9_000L)).isEqualTo(highlight)
        assertThat(activeHighlight(positionMs = 10_000L)).isNull()
    }

    @Test
    fun `a highlight in the first seconds stops being offered once reached`() {
        val early = highlight.copy(segment = listOf(5f, 5f))

        assertThat(activeHighlight(positionMs = 4_000L, segment = early)).isEqualTo(early)
        assertThat(activeHighlight(positionMs = 5_000L, segment = early)).isNull()
    }

    @Test
    fun `a highlight shown only on the seek bar offers no jump`() {
        assertThat(activeHighlight(positionMs = 1_000L, action = SponsorBlockAction.IGNORE)).isNull()
    }

    @Test
    fun `a jump already taken is not offered again`() {
        assertThat(activeHighlight(positionMs = 1_000L, skipped = setOf("highlight-id"))).isNull()
    }

    @Test
    fun `a whole-video label is not mistaken for a highlight`() {
        val label = SponsorBlockSegment("sponsor", listOf(0f, 0f), "label-id", "full")

        val active =
            findActiveManualSponsorSegment(
                sponsorSegments = listOf(label),
                currentPositionMs = 0L,
                skippedUuids = emptySet(),
                categoryActions = mapOf("sponsor" to SponsorBlockAction.SKIP_BUTTON),
                playbackEnded = false,
            )

        assertThat(active).isNull()
    }

    private fun activeWithOpening(
        positionMs: Long,
        openingAction: SponsorBlockAction,
    ) = findActiveManualSponsorSegment(
        sponsorSegments = listOf(SponsorBlockSegment("preview", listOf(0f, 68f), "opening-id", "skip"), highlight),
        currentPositionMs = positionMs,
        skippedUuids = emptySet(),
        categoryActions = mapOf("preview" to openingAction, "poi_highlight" to SponsorBlockAction.SKIP_BUTTON),
        playbackEnded = false,
    )

    @Test
    fun `the jump is offered after an opening that was skipped automatically`() {
        assertThat(activeWithOpening(70_000L, SponsorBlockAction.SKIP)).isEqualTo(highlight)
        assertThat(activeWithOpening(78_000L, SponsorBlockAction.SKIP)).isNull()
    }

    @Test
    fun `an opening that plays does not move the window`() {
        assertThat(activeWithOpening(5_000L, SponsorBlockAction.IGNORE)).isEqualTo(highlight)
        assertThat(activeWithOpening(70_000L, SponsorBlockAction.IGNORE)).isNull()
    }

    @Test
    fun `skipped segments back to back all move the window`() {
        val active =
            findActiveManualSponsorSegment(
                sponsorSegments =
                    listOf(
                        SponsorBlockSegment("intro", listOf(0f, 20f), "a", "skip"),
                        SponsorBlockSegment("sponsor", listOf(20.5f, 40f), "b", "skip"),
                        highlight,
                    ),
                currentPositionMs = 45_000L,
                skippedUuids = emptySet(),
                categoryActions = mapOf("poi_highlight" to SponsorBlockAction.SKIP_BUTTON),
                playbackEnded = false,
            )

        assertThat(active).isEqualTo(highlight)
    }
}
