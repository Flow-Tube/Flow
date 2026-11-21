package io.github.aedev.flow.data.images

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.utils.ThumbnailUrlResolver
import org.junit.Test

class DrawnSizeArtworkTest {
    private val post = "https://yt3.ggpht.com/abc=s640-c-fcrop64=1,00000000ffffffff-rw-nd-v1"

    @Test
    fun `sizes round up to a step and unknown sizes keep the fallback`() {
        assertThat(ImageSizeSteps.roundUp(154, unknown = 256)).isEqualTo(192)
        assertThat(ImageSizeSteps.roundUp(192, unknown = 256)).isEqualTo(192)
        assertThat(ImageSizeSteps.roundUp(1080, unknown = 256)).isEqualTo(1536)
        assertThat(ImageSizeSteps.roundUp(4000, unknown = 256)).isEqualTo(2048)
        assertThat(ImageSizeSteps.roundUp(0, unknown = 256)).isEqualTo(256)
    }

    @Test
    fun `a post image is asked for at its drawn width and keeps the feed crop`() {
        assertThat(PostImage(post).urls(1080, 608, crop = true))
            .containsExactly("https://yt3.ggpht.com/abc=w1536-c-fcrop64=1,00000000ffffffff-rw-nd-v1")
        assertThat(PostImage(post).urls(462, 260, crop = true))
            .containsExactly("https://yt3.ggpht.com/abc=w512-c-fcrop64=1,00000000ffffffff-rw-nd-v1")
    }

    @Test
    fun `a square crop asks for enough width to fill its height with a wide image`() {
        assertThat(PostImage(post).urls(900, 900, crop = true).single()).contains("=w2048")
        assertThat(PostImage(post).urls(500, 500, crop = true).single()).contains("=w1024")
    }

    @Test
    fun `a fitted post image only needs the box width`() {
        assertThat(PostImage(post, uncropped = true).urls(1080, 1430, crop = false))
            .containsExactly("https://yt3.ggpht.com/abc=w1536")
    }

    @Test
    fun `an unknown size keeps the full width post image`() {
        assertThat(PostImage(post, uncropped = true).urls(0, 0, crop = false))
            .containsExactly(ThumbnailUrlResolver.resolveCommunityPostImage(post, uncropped = true))
    }

    @Test
    fun `a small music tile takes mqdefault instead of hq720`() {
        val artwork = MusicArtwork("dQw4w9WgXcQ", "https://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg")

        assertThat(artwork.urls(154, 154)).containsExactly("https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg")
        assertThat(artwork.urls(0, 0)).containsExactly("https://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg")
        assertThat(artwork.urls(600, 600)).containsExactly("https://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg")
    }

    @Test
    fun `music artwork on Google's CDN is sized to the drawn step`() {
        val artwork = MusicArtwork("abc", "https://lh3.googleusercontent.com/xyz=w544-h544-l90-rj")

        assertThat(artwork.urls(154, 154)).containsExactly("https://lh3.googleusercontent.com/xyz=w192-h192-l90-rj")
    }

    @Test
    fun `a stored maxres cover stays maxres on the large player artwork`() {
        assertThat(ThumbnailUrlResolver.resolveMusicThumbnail("abc", "https://i.ytimg.com/vi/abc/maxresdefault.jpg", 1080))
            .isEqualTo("https://i.ytimg.com/vi/abc/maxresdefault.jpg")
    }

    @Test
    fun `square crops never take a tier with bars`() {
        assertThat(ThumbnailTierPolicy.squareCropName(192)).isEqualTo("mqdefault")
        assertThat(ThumbnailTierPolicy.squareCropName(256)).isEqualTo("hq720")
    }
}
