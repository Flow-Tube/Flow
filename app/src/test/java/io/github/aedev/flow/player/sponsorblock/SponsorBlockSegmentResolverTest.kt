package io.github.aedev.flow.player.sponsorblock

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.SponsorBlockAction
import io.github.aedev.flow.data.model.SponsorBlockSegment
import org.junit.Test

class SponsorBlockSegmentResolverTest {
    private fun segment(
        category: String,
        start: Float,
        end: Float,
        actionType: String = "skip",
        uuid: String = "$category-$start",
    ) = SponsorBlockSegment(category, listOf(start, end), uuid, actionType)

    private fun resolve(
        segments: List<SponsorBlockSegment>,
        posSec: Float,
        actions: Map<String, SponsorBlockAction>,
        skippedOnce: Set<String> = emptySet(),
    ) = SponsorBlockSegmentResolver.resolve(segments, posSec, actions, skippedOnce)

    private val intro = segment("intro", 0f, 36.996f)
    private val nonMusic = segment("music_offtopic", 0f, 36.983f)

    @Test
    fun `an auto-skip under a manual segment of another category is skipped`() {
        val hit = resolve(listOf(intro, nonMusic), 0f, mapOf("intro" to SponsorBlockAction.SKIP_BUTTON))

        assertThat(hit!!.skipsAutomatically).isTrue()
        assertThat(hit.segment).isEqualTo(nonMusic)
    }

    @Test
    fun `the 9b8erWuBA44 opening skips past both segments with intro on a button`() {
        val hit = resolve(listOf(intro, nonMusic), 0f, mapOf("intro" to SponsorBlockAction.SKIP_BUTTON))

        assertThat(hit!!.targetMs).isEqualTo(36_996L)
        assertThat(hit.covered).containsExactly(intro, nonMusic)
    }

    @Test
    fun `a disabled category does not hide a skip`() {
        val actions =
            mapOf(
                "intro" to SponsorBlockAction.DISABLED,
                "music_offtopic" to SponsorBlockAction.SKIP,
            )

        assertThat(resolve(listOf(intro, nonMusic), 10f, actions)!!.segment).isEqualTo(nonMusic)
    }

    @Test
    fun `seek bar only and disabled categories alone resolve to nothing`() {
        val actions =
            mapOf(
                "intro" to SponsorBlockAction.DISABLED,
                "music_offtopic" to SponsorBlockAction.IGNORE,
            )

        assertThat(resolve(listOf(intro, nonMusic), 10f, actions)).isNull()
    }

    @Test
    fun `overlapping and touching skips jump to the furthest end`() {
        val sponsor = segment("sponsor", 10f, 20f)
        val selfPromo = segment("selfpromo", 15f, 30f)
        val touching = segment("interaction", 30.03f, 40f)
        val apart = segment("filler", 41f, 50f)
        val actions = mapOf("selfpromo" to SponsorBlockAction.SKIP, "interaction" to SponsorBlockAction.SKIP)

        val hit = resolve(listOf(sponsor, selfPromo, touching, apart), 12f, actions)

        assertThat(hit!!.targetSec).isEqualTo(40f)
        assertThat(hit.chain).containsExactly(sponsor, selfPromo, touching)
    }

    @Test
    fun `a seek into overlapping segments lands past the chain`() {
        val sponsor = segment("sponsor", 10f, 20f)
        val selfPromo = segment("selfpromo", 18f, 25f)

        val hit = resolve(listOf(sponsor, selfPromo), 19f, mapOf("selfpromo" to SponsorBlockAction.SKIP))

        assertThat(hit!!.targetMs).isEqualTo(25_000L)
    }

    @Test
    fun `a skip once segment already skipped stops the chain`() {
        val sponsor = segment("sponsor", 10f, 20f)
        val selfPromo = segment("selfpromo", 20f, 30f)
        val actions = mapOf("selfpromo" to SponsorBlockAction.SKIP_ONCE)

        assertThat(resolve(listOf(sponsor, selfPromo), 12f, actions)!!.targetSec).isEqualTo(30f)
        assertThat(resolve(listOf(sponsor, selfPromo), 12f, actions, setOf(selfPromo.uuid))!!.targetSec).isEqualTo(20f)
    }

    @Test
    fun `mute beats a manual segment at the same position`() {
        val filler = segment("filler", 10f, 20f)
        val muted = segment("sponsor", 12f, 14f, actionType = "mute")
        val actions = mapOf("filler" to SponsorBlockAction.SKIP_BUTTON)

        val hit = resolve(listOf(filler, muted), 13f, actions)

        assertThat(hit!!.action).isEqualTo(SponsorBlockAction.MUTE)
        assertThat(hit.segment).isEqualTo(muted)
    }

    @Test
    fun `a point or whole-video label never wins`() {
        val highlight = segment("poi_highlight", 5f, 5f, actionType = "poi")
        val label = segment("sponsor", 0f, 0f, actionType = "full")
        val actions = mapOf("poi_highlight" to SponsorBlockAction.SKIP)

        assertThat(resolve(listOf(highlight, label), 5f, actions)).isNull()
        assertThat(resolve(listOf(highlight, label), 0f, actions)).isNull()
    }

    @Test
    fun `a handled segment leaves the next one in line`() {
        val toast = segment("filler", 10f, 20f)
        val button = segment("preview", 10f, 20f)
        val actions = mapOf("filler" to SponsorBlockAction.SHOW_TOAST, "preview" to SponsorBlockAction.SKIP_BUTTON)

        val hit = SponsorBlockSegmentResolver.resolve(listOf(toast, button), 12f, actions, emptySet(), setOf(toast.uuid))

        assertThat(hit!!.segment).isEqualTo(button)
    }

    @Test
    fun `a rewind re-arms a segment, also one that starts at the very beginning`() {
        val opening = segment("intro", 0f, 37f)
        val later = segment("sponsor", 60f, 70f)

        assertThat(SponsorBlockSegmentResolver.isRewoundBefore(0.4f, opening)).isTrue()
        assertThat(SponsorBlockSegmentResolver.isRewoundBefore(36.5f, opening)).isFalse()
        assertThat(SponsorBlockSegmentResolver.isRewoundBefore(58.9f, later)).isTrue()
        assertThat(SponsorBlockSegmentResolver.isRewoundBefore(59.5f, later)).isFalse()
    }
}
