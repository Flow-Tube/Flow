package io.github.aedev.flow.ui.components.videoplayer.motion

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.ui.components.videoplayer.MiniPlayerCorner
import org.junit.Test

class DraggablePlayerGeometryTest {
    private fun phone(
        currentSizeScale: Float = 1f,
        corner: MiniPlayerCorner = MiniPlayerCorner.BottomRight,
        isShrinkingToCorner: Boolean = false,
        videoAspectRatio: Float = 16f / 9f,
    ) = computeDraggablePlayerGeometry(
        screenWidth = 1080f,
        screenHeight = 2400f,
        statusBarHeight = 80f,
        margin = 24f,
        bottomNavPad = 200f,
        topBarPad = 168f,
        isLargeWindow = false,
        isTwoPaneWindow = false,
        miniPlayerScale = 0.45f,
        videoAspectRatio = videoAspectRatio,
        currentSizeScale = currentSizeScale,
        corner = corner,
        isShrinkingToCorner = isShrinkingToCorner,
        cachedTargetX = 0f,
        offsetXFallback = { 0f },
    )

    @Test
    fun `phone mini player rests in the bottom right by default`() {
        val g = phone()
        assertThat(g.baseMiniWidth).isWithin(0.01f).of(486f)
        assertThat(g.miniWidth).isWithin(0.01f).of(486f)
        assertThat(g.miniHeight).isWithin(0.01f).of(486f * 9f / 16f)
        assertThat(g.minX).isEqualTo(24f)
        assertThat(g.maxX).isWithin(0.01f).of(1080f - 486f - 24f)
        assertThat(g.minY).isEqualTo(80f + 168f + 24f)
        assertThat(g.maxY).isWithin(0.01f).of(2400f - g.miniHeight - 200f - 24f)
        assertThat(g.targetMiniX).isEqualTo(g.maxX)
        assertThat(g.targetMiniY).isEqualTo(g.maxY)
        assertThat(g.isWideMode).isFalse()
    }

    @Test
    fun `wide mode on a phone centres horizontally and keeps the corner row`() {
        val g = phone(currentSizeScale = 2.2f, corner = MiniPlayerCorner.TopLeft)
        assertThat(g.isWideMode).isTrue()
        assertThat(g.miniWidth).isWithin(0.01f).of(g.maxWideWidth)
        assertThat(g.targetMiniX).isEqualTo(g.stablePhoneCenteredX)
        assertThat(g.targetMiniY).isEqualTo(g.minY)
    }

    @Test
    fun `shrinking back to a corner targets the normal corner even while still wide`() {
        val g = phone(currentSizeScale = 2.2f, corner = MiniPlayerCorner.BottomLeft, isShrinkingToCorner = true)
        assertThat(g.targetMiniX).isEqualTo(g.normalTargetX)
        assertThat(g.targetMiniY).isEqualTo(g.normalTargetY)
        assertThat(g.normalTargetX).isEqualTo(24f)
    }

    @Test
    fun `a video wider than 16 by 9 is letterboxed in a 16 by 9 box`() {
        val g = phone(videoAspectRatio = 2.39f)
        assertThat(g.clampedAspect).isEqualTo(16f / 9f)
        assertThat(g.expandedVideoHeight).isWithin(0.01f).of(g.baseVideoHeight)
    }

    @Test
    fun `a rendition a few pixels off 16 by 9 still gets the 16 by 9 box`() {
        assertThat(playerBoxAspectRatio(1920f / 1088f)).isEqualTo(16f / 9f)
        assertThat(playerBoxAspectRatio(1920f / 1036f)).isEqualTo(16f / 9f)
    }

    @Test
    fun `a clearly taller video keeps its own shape`() {
        assertThat(playerBoxAspectRatio(4f / 3f)).isEqualTo(4f / 3f)
        assertThat(playerBoxAspectRatio(9f / 16f)).isEqualTo(9f / 16f)
    }

    @Test
    fun `a portrait video keeps the mini envelope inside the landscape box`() {
        val g = phone(videoAspectRatio = 9f / 16f)
        assertThat(g.clampedAspect).isLessThan(1f)
        assertThat(g.miniWidth).isWithin(0.01f).of(g.baseMiniWidth * g.clampedAspect)
        assertThat(g.expandedVideoHeight).isGreaterThan(g.baseVideoHeight)
        assertThat(g.visualMiniScale).isWithin(0.0001f).of(g.miniWidth / 1080f)
    }

    private fun large(
        isTwoPaneWindow: Boolean = false,
        maxMiniWidthPx: Float = Float.MAX_VALUE,
        detailPaneWidth: Float = 0f,
        currentSizeScale: Float = 1f,
        cachedTargetX: Float = 0f,
        offsetXFallback: Float = 0f,
        corner: MiniPlayerCorner = MiniPlayerCorner.BottomRight,
    ) = computeDraggablePlayerGeometry(
        screenWidth = 1600f,
        screenHeight = 2560f,
        statusBarHeight = 80f,
        margin = 24f,
        bottomNavPad = 200f,
        topBarPad = 168f,
        isLargeWindow = true,
        isTwoPaneWindow = isTwoPaneWindow,
        detailPaneWidth = detailPaneWidth,
        miniPlayerScale = 0.45f,
        maxMiniWidthPx = maxMiniWidthPx,
        videoAspectRatio = 16f / 9f,
        currentSizeScale = currentSizeScale,
        corner = corner,
        isShrinkingToCorner = false,
        cachedTargetX = cachedTargetX,
        offsetXFallback = { offsetXFallback },
    )

    @Test
    fun `the size preference applies at every window size`() {
        assertThat(phone().baseMiniWidth).isWithin(0.01f).of(1080f * 0.45f)
        assertThat(large().baseMiniWidth).isWithin(0.01f).of(1600f * 0.45f)
        assertThat(large(isTwoPaneWindow = true).baseMiniWidth).isWithin(0.01f).of(1600f * 0.45f)
    }

    @Test
    fun `a large window is bounded by an absolute ceiling rather than by a fraction of itself`() {
        // A fraction of a tablet is the wrong unit: the caller passes the width the size setting is
        // worth in dp, and the preference scales that instead of the screen.
        // 1600 x 0.45 is 720, so a ceiling under that is what actually binds.
        assertThat(large(maxMiniWidthPx = 500f).baseMiniWidth).isWithin(0.01f).of(500f)
        assertThat(large(isTwoPaneWindow = true, maxMiniWidthPx = 500f).baseMiniWidth)
            .isWithin(0.01f)
            .of(500f)
    }

    @Test
    fun `a ceiling the window is already under leaves the preference alone`() {
        assertThat(large(maxMiniWidthPx = 5000f).baseMiniWidth).isWithin(0.01f).of(1600f * 0.45f)
    }

    @Test
    fun `the wide cap is the full width on a compact window and a fraction on a large one`() {
        assertThat(phone().maxWideWidth).isWithin(0.01f).of(1080f - 48f)
        assertThat(large().maxWideWidth).isWithin(0.01f).of(1600f * 0.60f - 48f)
        assertThat(large(isTwoPaneWindow = true).maxWideWidth).isWithin(0.01f).of(1600f * 0.60f - 48f)
    }

    @Test
    fun `a two-pane window gives the expanded video the width left beside the detail pane`() {
        val g = large(isTwoPaneWindow = true, detailPaneWidth = 500f)
        assertThat(g.expandedVideoWidth).isWithin(0.01f).of(1100f)
        assertThat(g.baseVideoHeight).isWithin(0.01f).of(1100f * 9f / 16f)
        assertThat(g.expandedVideoHeight).isWithin(0.01f).of(g.baseVideoHeight)
        assertThat(g.visualMiniScale).isWithin(0.0001f).of(g.miniWidth / 1100f)
        assertThat(large(detailPaneWidth = 500f).expandedVideoWidth).isEqualTo(1600f)
    }

    @Test
    fun `wide mode on a large window keeps the cached corner x and the wide row`() {
        val g = large(currentSizeScale = 2.2f, cachedTargetX = 300f, corner = MiniPlayerCorner.BottomLeft)
        assertThat(g.isWideMode).isTrue()
        assertThat(g.miniWidth).isWithin(0.01f).of(g.maxWideWidth)
        assertThat(g.targetMiniX).isEqualTo(300f)
        assertThat(g.targetMiniY).isEqualTo(g.stableWideTargetY)
        assertThat(g.stableWideTargetY).isEqualTo(g.stableWideMaxY)
    }

    @Test
    fun `wide mode on a large window with no cached x clamps the live offset`() {
        // Pins current behaviour: a cached x of exactly 0 is read as "unknown" and the live offset
        // is used instead, clamped to the wide-mode drag bounds.
        val far = large(currentSizeScale = 2.2f, cachedTargetX = 0f, offsetXFallback = 5000f)
        assertThat(far.targetMiniX).isEqualTo(far.maxX)
        assertThat(far.maxX).isWithin(0.01f).of(1600f - far.miniWidth - 24f)

        val near = large(currentSizeScale = 2.2f, cachedTargetX = 0f, offsetXFallback = -50f)
        assertThat(near.targetMiniX).isEqualTo(near.minX)
        assertThat(near.minX).isEqualTo(24f)
    }

    @Test
    fun `with the keyboard closed the mini player stays where it rests`() {
        assertThat(keyboardSafeMiniY(2000f, 270f, 2400f, imeBottom = 0f, margin = 24f, minY = 272f)).isEqualTo(2000f)
    }

    @Test
    fun `a bottom corner mini player rides above the keyboard`() {
        val y = keyboardSafeMiniY(2000f, 270f, 2400f, imeBottom = 900f, margin = 24f, minY = 272f)

        assertThat(y).isEqualTo(1206f)
        assertThat(y + 270f).isAtMost(2400f - 900f)
    }

    @Test
    fun `a mini player already clear of the keyboard does not move`() {
        assertThat(keyboardSafeMiniY(300f, 270f, 2400f, imeBottom = 900f, margin = 24f, minY = 272f)).isEqualTo(300f)
    }

    @Test
    fun `a keyboard taller than the room left never pushes it under the top bar`() {
        assertThat(keyboardSafeMiniY(2000f, 270f, 2400f, imeBottom = 2000f, margin = 24f, minY = 272f)).isEqualTo(272f)
    }

    @Test
    fun `a 9 by 16 video leaves the page in reach on a 16 by 9 phone`() {
        // Redmi 4A: 360 x 640 dp at 2x, 24 dp status bar, 48 dp navigation bar, 120 dp of page.
        val ceiling = tallVideoMaxHeight(1280f, 48f, 96f, 240f, baseVideoHeight = 405f)
        assertThat(ceiling).isEqualTo(896f)
        assertThat(ceiling).isLessThan(720f * 16f / 9f)
    }

    @Test
    fun `a 9 by 16 video keeps its full height on a 20 by 9 phone`() {
        // 393 x 873 dp at 2.75x with gesture navigation: the box is 699 dp, the ceiling 705 dp.
        val density = 2.75f
        val width = 393f * density
        val ceiling = tallVideoMaxHeight(873f * density, 24f * density, 24f * density, 120f * density, width * 9f / 16f)
        assertThat(ceiling).isGreaterThan(width * 16f / 9f)
    }

    @Test
    fun `the ceiling never cuts below 16 by 9`() {
        assertThat(tallVideoMaxHeight(700f, 48f, 96f, 240f, baseVideoHeight = 405f)).isEqualTo(405f)
    }
}
