package io.github.aedev.flow.ui.components.videoplayer.overlay

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.SponsorBlockAction
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
}
