package io.github.aedev.flow.player.sponsorblock

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.SponsorBlockAction
import io.github.aedev.flow.data.model.SponsorBlockSegment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SponsorBlockHandlerTest {
    private val outro =
        SponsorBlockSegment(
            category = "outro",
            segment = listOf(156.5f, 157.2f),
            uuid = "outro-id",
            actionType = "skip",
        )

    private fun handler() =
        SponsorBlockHandler(CoroutineScope(UnconfinedTestDispatcher())).apply {
            setEnabled(true)
            loadSegmentsFromList("sh04x4jzCPw", listOf(outro))
        }

    @Test
    fun `entering a segment returns its end as the skip target`() {
        assertThat(handler().checkForSkip(156_700L)).isEqualTo(157_200L)
    }

    @Test
    fun `landing just short of the segment end does not re-skip`() {
        val handler = handler()
        handler.checkForSkip(156_700L)

        assertThat(handler.checkForSkip(156_400L)).isNull()
        assertThat(handler.checkForSkip(156_600L)).isNull()
    }

    @Test
    fun `rewinding well before the segment re-arms the skip`() {
        val handler = handler()
        handler.checkForSkip(156_700L)

        assertThat(handler.checkForSkip(120_000L)).isNull()
        assertThat(handler.checkForSkip(156_700L)).isEqualTo(157_200L)
    }

    private fun segment(
        category: String,
        start: Float,
        end: Float,
        actionType: String = "skip",
    ) = SponsorBlockSegment(category, listOf(start, end), "$category-id", actionType)

    private fun handlerWith(
        segment: SponsorBlockSegment,
        actions: Map<String, SponsorBlockAction> = emptyMap(),
    ) = SponsorBlockHandler(CoroutineScope(UnconfinedTestDispatcher())).apply {
        setEnabled(true)
        loadSegmentsFromList("video", listOf(segment))
        categoryActions = actions
    }

    @Test
    fun `filler with no stored action shows without skipping`() {
        assertThat(handlerWith(segment("filler", 10f, 20f)).checkForSkip(15_000L)).isNull()
    }

    @Test
    fun `preview with no stored action shows without skipping`() {
        assertThat(handlerWith(segment("preview", 10f, 20f)).checkForSkip(15_000L)).isNull()
    }

    @Test
    fun `filler set to ignore or notify is not skipped`() {
        val filler = segment("filler", 10f, 20f)

        assertThat(handlerWith(filler, mapOf("filler" to SponsorBlockAction.IGNORE)).checkForSkip(15_000L)).isNull()
        assertThat(handlerWith(filler, mapOf("filler" to SponsorBlockAction.SHOW_TOAST)).checkForSkip(15_000L)).isNull()
    }

    @Test
    fun `filler the user set to skip is skipped`() {
        val handler = handlerWith(segment("filler", 10f, 20f), mapOf("filler" to SponsorBlockAction.SKIP))

        assertThat(handler.checkForSkip(15_000L)).isEqualTo(20_000L)
    }

    @Test
    fun `a sponsor with no stored action is still skipped`() {
        assertThat(handlerWith(segment("sponsor", 10f, 20f)).checkForSkip(15_000L)).isEqualTo(20_000L)
    }

    @Test
    fun `a whole-video label never triggers an action`() {
        val label = segment("exclusive_access", 0f, 0f, actionType = "full")
        val handler = handlerWith(label, mapOf("exclusive_access" to SponsorBlockAction.SKIP))

        assertThat(handler.checkForSkip(0L)).isNull()
        assertThat(handler.checkForSkip(5_000L)).isNull()
    }

    @Test
    fun `a download's stored segments do nothing while SponsorBlock is off`() {
        val handler = SponsorBlockHandler(CoroutineScope(UnconfinedTestDispatcher()))
        handler.loadSegmentsFromList("video", listOf(segment("sponsor", 10f, 20f)))

        assertThat(handler.checkForSkip(15_000L)).isNull()
        assertThat(handler.sponsorSegments.value).isEmpty()

        handler.setEnabled(true)
        assertThat(handler.sponsorSegments.value).hasSize(1)
        assertThat(handler.checkForSkip(15_000L)).isEqualTo(20_000L)
    }

    @Test
    fun `switched off for this video, nothing is skipped and the markers stay`() {
        val handler = handlerWith(segment("sponsor", 10f, 20f))
        handler.setDisabledForCurrentVideo(true, currentPositionMs = 0L)

        assertThat(handler.disabledForCurrentVideo.value).isTrue()
        assertThat(handler.checkForSkip(15_000L)).isNull()
        assertThat(handler.sponsorSegments.value).hasSize(1)
    }

    @Test
    fun `the same video prepared again stays off, the next video starts with the setting`() {
        val sponsor = segment("sponsor", 10f, 20f)
        val handler = handlerWith(sponsor)
        handler.setDisabledForCurrentVideo(true, currentPositionMs = 0L)

        handler.reset()
        handler.loadSegmentsFromList("video", listOf(sponsor))
        assertThat(handler.checkForSkip(15_000L)).isNull()

        handler.reset()
        handler.loadSegmentsFromList("next", listOf(sponsor))
        assertThat(handler.disabledForCurrentVideo.value).isFalse()
        assertThat(handler.checkForSkip(15_000L)).isEqualTo(20_000L)
    }

    @Test
    fun `switching back on inside a segment leaves that segment alone`() {
        val handler = handlerWith(segment("sponsor", 10f, 20f))
        handler.setDisabledForCurrentVideo(true, currentPositionMs = 0L)
        handler.setDisabledForCurrentVideo(false, currentPositionMs = 12_000L)

        assertThat(handler.checkForSkip(15_000L)).isNull()
        assertThat(handler.checkForSkip(2_000L)).isNull()
        assertThat(handler.checkForSkip(15_000L)).isEqualTo(20_000L)
    }

    @Test
    fun `a mute segment mutes, and leaving it, switching off or a new video unmutes`() =
        runTest {
            val events = mutableListOf<Boolean>()
            val handler = handlerWith(segment("sponsor", 10f, 20f), mapOf("sponsor" to SponsorBlockAction.MUTE))
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { handler.muteEvent.toList(events) }

            handler.checkForSkip(12_000L)
            handler.checkForSkip(21_000L)
            assertThat(events).containsExactly(true, false).inOrder()

            handler.checkForSkip(5_000L)
            handler.checkForSkip(12_000L)
            handler.setDisabledForCurrentVideo(true, currentPositionMs = 12_000L)
            assertThat(events).containsExactly(true, false, true, false).inOrder()

            handler.setDisabledForCurrentVideo(false, currentPositionMs = 0L)
            handler.checkForSkip(12_000L)
            handler.loadSegmentsFromList("next", emptyList())
            assertThat(events).containsExactly(true, false, true, false, true, false).inOrder()
        }

    @Test
    fun `skip once skips the first time only, also after the same video is prepared again`() {
        val sponsor = segment("sponsor", 10f, 20f)
        val handler = handlerWith(sponsor, mapOf("sponsor" to SponsorBlockAction.SKIP_ONCE))

        assertThat(handler.checkForSkip(15_000L)).isEqualTo(20_000L)
        assertThat(handler.skippedOnce.value).containsExactly(sponsor.uuid)

        handler.checkForSkip(2_000L)
        assertThat(handler.checkForSkip(15_000L)).isNull()

        handler.reset()
        handler.loadSegmentsFromList("video", listOf(sponsor))
        assertThat(handler.checkForSkip(15_000L)).isNull()

        handler.reset()
        handler.loadSegmentsFromList("next", listOf(sponsor))
        assertThat(handler.skippedOnce.value).isEmpty()
        assertThat(handler.checkForSkip(15_000L)).isEqualTo(20_000L)
    }

    @Test
    fun `skip button, seek bar only and disabled never skip`() {
        val sponsor = segment("sponsor", 10f, 20f)
        listOf(SponsorBlockAction.SKIP_BUTTON, SponsorBlockAction.IGNORE, SponsorBlockAction.DISABLED).forEach { action ->
            assertThat(handlerWith(sponsor, mapOf("sponsor" to action)).checkForSkip(15_000L)).isNull()
        }
    }

    @Test
    fun `a segment submitted as a mute is muted where its category would skip`() =
        runTest {
            val events = mutableListOf<Boolean>()
            val handler = handlerWith(segment("sponsor", 10f, 20f, actionType = "mute"))
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { handler.muteEvent.toList(events) }

            assertThat(handler.checkForSkip(12_000L)).isNull()
            assertThat(events).containsExactly(true)
        }

    @Test
    fun `disabled categories are left off the seek bar`() =
        runTest {
            val handler = handlerWith(segment("filler", 10f, 20f), mapOf("filler" to SponsorBlockAction.DISABLED))
            val visible = mutableListOf<List<SponsorBlockSegment>>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { handler.visibleSegments.toList(visible) }

            assertThat(visible.last()).isEmpty()

            handler.categoryActions = mapOf("filler" to SponsorBlockAction.IGNORE)
            assertThat(visible.last()).hasSize(1)
        }

    @Test
    fun `a seek into a segment at the very start lands on its end every time`() {
        val handler = handlerWith(segment("music_offtopic", 0f, 148.99f))

        assertThat(handler.skipTargetForSeek(140_000L)).isEqualTo(148_990L)
        assertThat(handler.skipTargetForSeek(138_990L)).isEqualTo(148_990L)
        assertThat(handler.skipTargetForSeek(0L)).isEqualTo(148_990L)
    }

    @Test
    fun `asking where a seek lands skips nothing`() {
        val sponsor = segment("sponsor", 10f, 20f)
        val handler = handlerWith(sponsor, mapOf("sponsor" to SponsorBlockAction.SKIP_ONCE))

        assertThat(handler.seekLandingFor(15_000L)).isEqualTo(20_000L)
        assertThat(handler.seekLandingFor(15_000L)).isEqualTo(20_000L)
        assertThat(handler.seekLandingFor(9_999L)).isNull()
        assertThat(handler.skippedOnce.value).isEmpty()
        assertThat(handler.skipTargetForSeek(15_000L)).isEqualTo(20_000L)
    }

    @Test
    fun `a seek outside every segment goes where it was asked`() {
        val handler = handlerWith(segment("sponsor", 10f, 20f))

        assertThat(handler.skipTargetForSeek(9_999L)).isNull()
        assertThat(handler.skipTargetForSeek(20_000L)).isNull()
    }

    @Test
    fun `after a seek lands on the segment end the tick does not skip it again`() {
        val handler = handlerWith(segment("sponsor", 10f, 20f))
        handler.skipTargetForSeek(15_000L)

        assertThat(handler.checkForSkip(19_900L)).isNull()
    }

    @Test
    fun `skip once skips the first seek and then lets the user stay`() {
        val sponsor = segment("sponsor", 10f, 20f)
        val handler = handlerWith(sponsor, mapOf("sponsor" to SponsorBlockAction.SKIP_ONCE))

        assertThat(handler.skipTargetForSeek(15_000L)).isEqualTo(20_000L)
        assertThat(handler.skippedOnce.value).containsExactly(sponsor.uuid)
        assertThat(handler.skipTargetForSeek(15_000L)).isNull()
    }

    @Test
    fun `a seek into a segment that does not skip by itself stays there`() {
        val sponsor = segment("sponsor", 10f, 20f)
        val kept =
            listOf(
                SponsorBlockAction.SKIP_BUTTON,
                SponsorBlockAction.SHOW_TOAST,
                SponsorBlockAction.MUTE,
                SponsorBlockAction.IGNORE,
                SponsorBlockAction.DISABLED,
            )

        kept.forEach { action ->
            assertThat(handlerWith(sponsor, mapOf("sponsor" to action)).skipTargetForSeek(15_000L)).isNull()
        }
    }

    @Test
    fun `a seek is left alone while SponsorBlock is off for this video`() {
        val handler = handlerWith(segment("sponsor", 10f, 20f))
        handler.setDisabledForCurrentVideo(true, currentPositionMs = 0L)

        assertThat(handler.skipTargetForSeek(15_000L)).isNull()
    }

    @Test
    fun `only the first skip of a segment in a video counts`() =
        runTest {
            val sponsor = segment("sponsor", 10f, 20f)
            val handler = handlerWith(sponsor)
            val skips = mutableListOf<SponsorBlockSkip>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { handler.skipEvent.toList(skips) }

            handler.checkForSkip(15_000L)
            handler.skipTargetForSeek(12_000L)
            handler.reset()
            handler.loadSegmentsFromList("video", listOf(sponsor))
            handler.skipTargetForSeek(12_000L)
            assertThat(skips.map { it.firstInVideo }).containsExactly(true, false, false).inOrder()

            handler.reset()
            handler.loadSegmentsFromList("next", listOf(sponsor))
            handler.skipTargetForSeek(12_000L)
            assertThat(skips.last().firstInVideo).isTrue()
        }
}
