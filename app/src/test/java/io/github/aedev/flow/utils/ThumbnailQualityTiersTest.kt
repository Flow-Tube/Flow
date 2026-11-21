package io.github.aedev.flow.utils

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.images.YouTubeThumbnail
import io.github.aedev.flow.data.local.ThumbnailQuality
import org.junit.Test

class ThumbnailQualityTiersTest {
    private val id = "dQw4w9WgXcQ"

    private fun tiers(
        quality: ThumbnailQuality,
        portrait: Boolean = false,
    ) = YouTubeThumbnail(id, quality, portrait).urls(0, 0).filter { it.endsWith(".jpg") }.map { it.substringAfterLast('/') }

    @Test
    fun `high is the ladder every card loaded before the setting existed`() {
        assertThat(tiers(ThumbnailQuality.HIGH)).containsExactly("hq720.jpg", "hqdefault.jpg").inOrder()
    }

    @Test
    fun `each tier is asked for as WebP before JPEG`() {
        assertThat(YouTubeThumbnail(id, ThumbnailQuality.MEDIUM).urls(0, 0))
            .containsExactly(
                "https://i.ytimg.com/vi_webp/$id/sddefault.webp",
                "https://i.ytimg.com/vi/$id/sddefault.jpg",
                "https://i.ytimg.com/vi_webp/$id/hqdefault.webp",
                "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            ).inOrder()
    }

    @Test
    fun `medium and low start smaller and still end on the tier every video has`() {
        assertThat(tiers(ThumbnailQuality.MEDIUM)).containsExactly("sddefault.jpg", "hqdefault.jpg").inOrder()
        assertThat(tiers(ThumbnailQuality.LOW)).containsExactly("mqdefault.jpg", "hqdefault.jpg").inOrder()
    }

    @Test
    fun `a portrait card never drops below hqdefault`() {
        assertThat(tiers(ThumbnailQuality.LOW, portrait = true)).containsExactly("hqdefault.jpg")
        assertThat(tiers(ThumbnailQuality.MEDIUM, portrait = true)).containsExactly("sddefault.jpg", "hqdefault.jpg").inOrder()
    }

    @Test
    fun `a DeArrow thumbnail stays first at every quality`() {
        val deArrow = "https://dearrow-thumb.ajay.app/api/v1/getThumbnail?videoID=$id&time=12"

        (ThumbnailQuality.entries - ThumbnailQuality.OFF).forEach { quality ->
            assertThat(ThumbnailUrlResolver.resolveVideoThumbnailModels(id, deArrow, quality).first()).isEqualTo(deArrow)
        }
    }

    @Test
    fun `the video id is recovered from a stored portrait or webp url`() {
        val stored = "https://i.ytimg.com/vi_webp/$id/oar2.webp?sqp=abc"

        assertThat(ThumbnailUrlResolver.resolveVideoThumbnailModels("", stored, ThumbnailQuality.LOW))
            .containsExactly(YouTubeThumbnail(id, ThumbnailQuality.LOW))
    }

    @Test
    fun `the network picks which preference applies`() {
        assertThat(ThumbnailQuality.effective(isWifi = true, wifi = ThumbnailQuality.HIGH, cellular = ThumbnailQuality.LOW))
            .isEqualTo(ThumbnailQuality.HIGH)
        assertThat(ThumbnailQuality.effective(isWifi = false, wifi = ThumbnailQuality.HIGH, cellular = ThumbnailQuality.LOW))
            .isEqualTo(ThumbnailQuality.LOW)
    }

    @Test
    fun `an unknown or missing stored value reads as high`() {
        assertThat(ThumbnailQuality.fromName(null)).isEqualTo(ThumbnailQuality.HIGH)
        assertThat(ThumbnailQuality.fromName("ULTRA")).isEqualTo(ThumbnailQuality.HIGH)
        assertThat(ThumbnailQuality.fromName("LOW")).isEqualTo(ThumbnailQuality.LOW)
    }

    @Test
    fun `off fetches nothing from the network, DeArrow included`() {
        val deArrow = "https://dearrow-thumb.ajay.app/api/v1/getThumbnail?videoID=$id&time=12"

        assertThat(tiers(ThumbnailQuality.OFF)).isEmpty()
        assertThat(tiers(ThumbnailQuality.OFF, portrait = true)).isEmpty()
        assertThat(ThumbnailUrlResolver.resolveVideoThumbnailModels(id, deArrow, ThumbnailQuality.OFF)).isEmpty()
    }

    @Test
    fun `off still shows a cover stored on the device`() {
        val saved = "file:///data/user/0/io.github.aedev.flow/files/thumbs/$id.jpg"
        val mediaStore = "content://media/external/video/media/42"

        assertThat(ThumbnailUrlResolver.resolveVideoThumbnailModels(id, saved, ThumbnailQuality.OFF)).containsExactly(saved)
        assertThat(ThumbnailUrlResolver.resolveVideoThumbnailModels("local_42", mediaStore, ThumbnailQuality.OFF))
            .containsExactly(mediaStore)
    }

    @Test
    fun `a device file never falls back to a YouTube thumbnail`() {
        assertThat(ThumbnailUrlResolver.resolveVideoThumbnailModels("local_42", null)).isEmpty()
        assertThat(ThumbnailUrlResolver.resolveVideoThumbnailModels("local_42", "content://media/external/video/media/42"))
            .containsExactly("content://media/external/video/media/42")
    }
}
