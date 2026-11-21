package io.github.aedev.flow.data.images

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.images.YouTubeThumbnailTier.HQ
import io.github.aedev.flow.data.images.YouTubeThumbnailTier.HQ720
import io.github.aedev.flow.data.images.YouTubeThumbnailTier.MQ
import io.github.aedev.flow.data.images.YouTubeThumbnailTier.SD
import io.github.aedev.flow.data.local.ThumbnailQuality
import org.junit.Test

class ThumbnailTierPolicyTest {
    private fun px(
        dp: Int,
        density: Float,
    ) = (dp * density).toInt()

    private fun landscape(
        widthPx: Int,
        quality: ThumbnailQuality = ThumbnailQuality.HIGH,
    ) = ThumbnailTierPolicy.pick(widthPx, widthPx * 9 / 16, quality, portrait = false)

    @Test
    fun `a full width phone card keeps hq720`() {
        assertThat(landscape(px(392, 2.75f))).isEqualTo(HQ720)
        assertThat(landscape(px(360, 3f))).isEqualTo(HQ720)
    }

    @Test
    fun `a phone row thumbnail drops to hqdefault`() {
        assertThat(landscape(px(168, 2.75f))).isEqualTo(HQ)
        assertThat(landscape(px(152, 2.75f))).isEqualTo(HQ)
    }

    @Test
    fun `a small row on a low density phone needs only mqdefault`() {
        assertThat(landscape(px(152, 2f))).isEqualTo(MQ)
    }

    @Test
    fun `tablet grids step through the middle tiers`() {
        assertThat(landscape(px(300, 2f))).isEqualTo(SD)
        assertThat(landscape(px(400, 2f))).isEqualTo(HQ720)
    }

    @Test
    fun `a tier is accepted down to ninety percent of the drawn width`() {
        assertThat(landscape(533)).isEqualTo(HQ)
        assertThat(landscape(534)).isEqualTo(SD)
        assertThat(landscape(355)).isEqualTo(MQ)
        assertThat(landscape(356)).isEqualTo(HQ)
    }

    @Test
    fun `the quality setting caps the tier`() {
        val wide = px(392, 2.75f)
        assertThat(landscape(wide, ThumbnailQuality.MEDIUM)).isEqualTo(SD)
        assertThat(landscape(wide, ThumbnailQuality.LOW)).isEqualTo(MQ)
        assertThat(landscape(wide, ThumbnailQuality.OFF)).isNull()
        assertThat(landscape(px(152, 2f), ThumbnailQuality.MEDIUM)).isEqualTo(MQ)
    }

    @Test
    fun `nothing ever goes above hq720`() {
        assertThat(landscape(3840)).isEqualTo(HQ720)
    }

    @Test
    fun `an unknown size uses the ceiling`() {
        assertThat(ThumbnailTierPolicy.pick(0, 0, ThumbnailQuality.HIGH, portrait = false)).isEqualTo(HQ720)
        assertThat(ThumbnailTierPolicy.pick(-1, -1, ThumbnailQuality.MEDIUM, portrait = true)).isEqualTo(SD)
    }

    @Test
    fun `one known side is enough`() {
        assertThat(ThumbnailTierPolicy.pick(0, 270, ThumbnailQuality.HIGH, portrait = false)).isEqualTo(HQ)
        assertThat(ThumbnailTierPolicy.pick(462, 0, ThumbnailQuality.HIGH, portrait = false)).isEqualTo(HQ)
    }

    @Test
    fun `a portrait crop compares heights`() {
        val shortsWidth = px(180, 2.75f)
        val shortsHeight = px(320, 2.75f)
        assertThat(ThumbnailTierPolicy.pick(shortsWidth, shortsHeight, ThumbnailQuality.HIGH, portrait = true)).isEqualTo(HQ720)
        assertThat(ThumbnailTierPolicy.pick(214, 380, ThumbnailQuality.HIGH, portrait = true)).isEqualTo(SD)
        assertThat(ThumbnailTierPolicy.pick(shortsWidth, 0, ThumbnailQuality.HIGH, portrait = true)).isEqualTo(HQ720)
    }

    @Test
    fun `a portrait crop never drops below hqdefault`() {
        assertThat(ThumbnailTierPolicy.pick(90, 160, ThumbnailQuality.HIGH, portrait = true)).isEqualTo(HQ)
        assertThat(ThumbnailTierPolicy.pick(900, 1600, ThumbnailQuality.LOW, portrait = true)).isEqualTo(HQ)
    }

    @Test
    fun `thumbnail walk ends on hqdefault`() {
        assertThat(ThumbnailTierPolicy.thumbnailNames(HQ720)).containsExactly("hq720", "hqdefault").inOrder()
        assertThat(ThumbnailTierPolicy.thumbnailNames(SD)).containsExactly("sddefault", "hqdefault").inOrder()
        assertThat(ThumbnailTierPolicy.thumbnailNames(HQ)).containsExactly("hqdefault")
        assertThat(ThumbnailTierPolicy.thumbnailNames(MQ)).containsExactly("mqdefault", "hqdefault").inOrder()
    }

    @Test
    fun `frame walk steps down to hq2 and ends on hqdefault`() {
        assertThat(ThumbnailTierPolicy.frameNames(HQ720)).containsExactly("maxres2", "sd2", "hq2", "hqdefault").inOrder()
        assertThat(ThumbnailTierPolicy.frameNames(SD)).containsExactly("sd2", "hq2", "hqdefault").inOrder()
        assertThat(ThumbnailTierPolicy.frameNames(HQ)).containsExactly("hq2", "hqdefault").inOrder()
        assertThat(ThumbnailTierPolicy.frameNames(MQ)).containsExactly("mq2", "hq2", "hqdefault").inOrder()
    }
}
