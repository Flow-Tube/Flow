package io.github.aedev.flow.player.stream

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The spec below is the real `playerStoryboardSpecRenderer.spec` for a 583 s video, and the
 * expectations were checked against the live sheets: every URL built this way returned a JPEG, and
 * the crops at 0/60/180/400/580 s were the right frames.
 */
private const val BASE =
    "https://i.ytimg.com/sb/3xngArcFpek/storyboard3_L\$L/\$N.jpg?sqp=-oaymwGhAUg48quKqQOYAYgBAZUB"

private const val SPEC =
    BASE +
        "|48#27#100#10#10#0#default#rs\$AOn4CLDHlKZRJY7QaV07_G4IfgGnNPp5Gw" +
        "|80#45#118#10#10#5000#M\$M#rs\$AOn4CLDPw68sp1OS6-dzM4d6CF9-5XmwgQ" +
        "|160#90#118#5#5#5000#M\$M#rs\$AOn4CLBlGUGTrWBNsfOLXoVoVXTtJQdCVw"

private const val DURATION_MS = 583_000L

class StoryboardSpecTest {
    private fun levels() = StoryboardSpec.parse(SPEC, DURATION_MS)

    @Test
    fun `every level of the spec is parsed`() {
        val levels = levels()

        assertThat(levels).hasSize(3)
        assertThat(levels.map { it.thumbnailWidth }).containsExactly(48, 80, 160).inOrder()
        assertThat(levels.map { it.frameCount }).containsExactly(100, 118, 118).inOrder()
        assertThat(levels.map { it.columns to it.rows })
            .containsExactly(10 to 10, 10 to 10, 5 to 5)
            .inOrder()
    }

    @Test
    fun `the coarsest level spreads its frames across the whole video`() {
        // It declares an interval of 0; 583s over 100 frames is 5830ms each.
        assertThat(levels().first().intervalMs).isEqualTo(5_830L)
    }

    @Test
    fun `the sheet url substitutes level, sheet and signature`() {
        val tile = levels()[2].tileAt(0L)!!

        assertThat(tile.sheetUrl).startsWith("https://i.ytimg.com/sb/3xngArcFpek/storyboard3_L2/M0.jpg")
        assertThat(tile.sheetUrl).endsWith("&sigh=rs\$AOn4CLBlGUGTrWBNsfOLXoVoVXTtJQdCVw")
        assertThat(tile.sheetUrl).doesNotContain("\$L")
        assertThat(tile.sheetUrl).doesNotContain("\$N")
        assertThat(tile.sheetUrl).doesNotContain("\$M")
    }

    @Test
    fun `tiles land on the frames verified against the live sheets`() {
        val level = levels()[2]

        val start = level.tileAt(0L)!!
        assertThat(start.sheetUrl).contains("/M0.jpg")
        assertThat(start.left to start.top).isEqualTo(0 to 0)

        val oneMinute = level.tileAt(60_000L)!!
        assertThat(oneMinute.sheetUrl).contains("/M0.jpg")
        assertThat(oneMinute.left to oneMinute.top).isEqualTo(320 to 180)

        val threeMinutes = level.tileAt(180_000L)!!
        assertThat(threeMinutes.sheetUrl).contains("/M1.jpg")
        assertThat(threeMinutes.left to threeMinutes.top).isEqualTo(160 to 180)

        val late = level.tileAt(400_000L)!!
        assertThat(late.sheetUrl).contains("/M3.jpg")
        assertThat(late.left to late.top).isEqualTo(0 to 90)
    }

    @Test
    fun `the final sheet reports only the rows it actually holds`() {
        // 118 frames, 25 per sheet: the fifth sheet carries 18, so four rows, not five.
        val tile = levels()[2].tileAt(580_000L)!!

        assertThat(tile.sheetUrl).contains("/M4.jpg")
        assertThat(tile.sheetWidth).isEqualTo(800)
        assertThat(tile.sheetHeight).isEqualTo(360)
        assertThat(tile.top + tile.height).isAtMost(tile.sheetHeight)
    }

    @Test
    fun `a full sheet reports its full height`() {
        val tile = levels()[2].tileAt(0L)!!

        assertThat(tile.sheetWidth).isEqualTo(800)
        assertThat(tile.sheetHeight).isEqualTo(450)
    }

    @Test
    fun `a position past the end clamps to the last frame`() {
        val level = levels()[2]

        val beyond = level.tileAt(DURATION_MS * 10)!!
        val last = level.tileAt(117 * 5_000L)!!

        assertThat(beyond).isEqualTo(last)
    }

    @Test
    fun `a negative position clamps to the first frame`() {
        val tile = levels()[2].tileAt(-5_000L)!!

        assertThat(tile.left to tile.top).isEqualTo(0 to 0)
        assertThat(tile.sheetUrl).contains("/M0.jpg")
    }

    @Test
    fun `the level closest to the drawn width is chosen`() {
        val levels = levels()

        assertThat(StoryboardSpec.levelFor(levels, 160)?.thumbnailWidth).isEqualTo(160)
        assertThat(StoryboardSpec.levelFor(levels, 90)?.thumbnailWidth).isEqualTo(80)
        assertThat(StoryboardSpec.levelFor(levels, 40)?.thumbnailWidth).isEqualTo(48)
    }

    @Test
    fun `a video with no storyboard yields no levels`() {
        assertThat(StoryboardSpec.parse(null, DURATION_MS)).isEmpty()
        assertThat(StoryboardSpec.parse("", DURATION_MS)).isEmpty()
        assertThat(StoryboardSpec.parse("https://example.test/sb.jpg", DURATION_MS)).isEmpty()
    }

    @Test
    fun `a spec whose base carries no tokens is rejected`() {
        val malformed = "https://example.test/sb.jpg|160#90#118#5#5#5000#M\$M#rs\$sig"

        assertThat(StoryboardSpec.parse(malformed, DURATION_MS)).isEmpty()
    }

    @Test
    fun `a level with missing or unusable fields is skipped rather than failing the spec`() {
        val partial =
            BASE +
                "|160#90#118#5#5" +
                "|0#90#118#5#5#5000#M\$M#rs\$sig" +
                "|160#90#118#5#5#5000#M\$M#rs\$good"

        val levels = StoryboardSpec.parse(partial, DURATION_MS)

        assertThat(levels).hasSize(1)
        assertThat(levels.single().tileAt(0L)!!.sheetUrl).endsWith("&sigh=rs\$good")
    }

    @Test
    fun `a zero-interval level with no duration is unusable`() {
        val spec = BASE + "|48#27#100#10#10#0#default#rs\$sig"

        assertThat(StoryboardSpec.parse(spec, durationMs = 0L)).isEmpty()
    }

    private val liveSpec = "https://i.ytimg.com/sb/abc/storyboard_live_90_3x3_b2/M\$M.jpg?rs=sig#159#90#3#3"

    private fun liveLevel(locator: LiveSegmentLocator) = StoryboardSpec.parseLive(liveSpec, 5_000L, locator).single()

    @Test
    fun `a live spec is one level of segment-numbered frames`() {
        val level = liveLevel { null }

        assertThat(level.isLive).isTrue()
        assertThat(level.thumbnailWidth to level.thumbnailHeight).isEqualTo(159 to 90)
        assertThat(level.columns to level.rows).isEqualTo(3 to 3)
        assertThat(level.intervalMs).isEqualTo(5_000L)
    }

    @Test
    fun `a live frame is found by its segment number`() {
        // Segment 1_000 is frame 1 of sheet 111 (nine frames a sheet): top row, middle column.
        val tile = liveLevel { LiveSegmentPosition(sequence = 1_000, headSequence = 2_000) }.tileAt(0L)!!

        assertThat(tile.sheetUrl).isEqualTo("https://i.ytimg.com/sb/abc/storyboard_live_90_3x3_b2/M111.jpg?rs=sig")
        assertThat(tile.left to tile.top).isEqualTo(159 to 0)
        assertThat(tile.sheetWidth to tile.sheetHeight).isEqualTo(477 to 270)
    }

    @Test
    fun `the newest two sheets of a live stream have no frames yet`() {
        val head = 2_000L
        fun tileFor(sequence: Long) = liveLevel { LiveSegmentPosition(sequence, head) }.tileAt(0L)

        assertThat(tileFor(head - 18)).isNotNull()
        assertThat(tileFor(head - 17)).isNull()
        assertThat(tileFor(head)).isNull()
        assertThat(tileFor(-1)).isNull()
    }

    @Test
    fun `a live spec without a sheet number or with missing fields is ignored`() {
        assertThat(StoryboardSpec.parseLive("https://i.ytimg.com/sb/abc/M0.jpg#159#90#3#3", 5_000L) { null }).isEmpty()
        assertThat(StoryboardSpec.parseLive("https://i.ytimg.com/sb/abc/M\$M.jpg#159#90", 5_000L) { null }).isEmpty()
        assertThat(StoryboardSpec.parseLive(null, 5_000L) { null }).isEmpty()
        assertThat(StoryboardSpec.parseLive(liveSpec, 0L) { null }).isEmpty()
    }
}
