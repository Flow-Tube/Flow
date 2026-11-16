package io.github.aedev.flow.utils

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.ThumbnailQuality
import org.junit.Test

class ThumbnailUrlResolverTest {
    @Test
    fun `normalizes jpg thumbnail variants to hq720`() {
        val result =
            ThumbnailUrlResolver.normalizeVideoThumbnail(
                "abc123",
                "https://i.ytimg.com/vi/abc123/hqdefault.jpg",
            )

        assertThat(result).isEqualTo("https://i.ytimg.com/vi/abc123/hq720.jpg")
    }

    @Test
    fun `normalizes query and webp variants to hq720`() {
        val result =
            ThumbnailUrlResolver.normalizeVideoThumbnail(
                "fallback",
                "https://i.ytimg.com/vi_webp/abc123/mqdefault.webp?sqp=abc",
            )

        assertThat(result).isEqualTo("https://i.ytimg.com/vi/abc123/hq720.jpg")
    }

    @Test
    fun `keeps non YouTube thumbnails unchanged`() {
        val raw = "https://example.com/thumb.jpg"

        assertThat(ThumbnailUrlResolver.normalizeVideoThumbnail("abc123", raw)).isEqualTo(raw)
    }

    @Test
    fun `builds fallback when raw url is blank`() {
        assertThat(ThumbnailUrlResolver.normalizeVideoThumbnail("abc123", ""))
            .isEqualTo("https://i.ytimg.com/vi/abc123/hq720.jpg")
    }

    @Test
    fun `card candidates never request maxresdefault`() {
        val candidates = ThumbnailUrlResolver.youtubeThumbnailCandidates(VIDEO_ID)

        assertThat(candidates)
            .containsExactly(
                "https://i.ytimg.com/vi/$VIDEO_ID/hq720.jpg",
                "https://i.ytimg.com/vi/$VIDEO_ID/hqdefault.jpg",
            ).inOrder()
    }

    @Test
    fun `card candidates are empty for a blank video id`() {
        assertThat(ThumbnailUrlResolver.youtubeThumbnailCandidates("   ")).isEmpty()
    }

    @Test
    fun `a stored maxres url is downgraded to card tiers`() {
        val stored = "https://i.ytimg.com/vi/$VIDEO_ID/maxresdefault.jpg"

        val candidates = ThumbnailUrlResolver.resolveVideoThumbnailCandidates(VIDEO_ID, stored)

        assertThat(candidates).doesNotContain(stored)
        assertThat(candidates.first()).isEqualTo("https://i.ytimg.com/vi/$VIDEO_ID/hq720.jpg")
    }

    @Test
    fun `a non youtube thumbnail is kept ahead of the youtube fallbacks`() {
        val raw = "https://example.com/custom/art.png"

        val candidates = ThumbnailUrlResolver.resolveVideoThumbnailCandidates(VIDEO_ID, raw)

        assertThat(candidates.first()).isEqualTo(raw)
        assertThat(candidates).hasSize(3)
    }

    @Test
    fun `candidates recover the video id from the raw url`() {
        val raw = "https://i.ytimg.com/vi/$OTHER_ID/hqdefault.jpg"

        val candidates = ThumbnailUrlResolver.resolveVideoThumbnailCandidates("", raw)

        assertThat(candidates)
            .containsExactly(
                "https://i.ytimg.com/vi/$OTHER_ID/hq720.jpg",
                "https://i.ytimg.com/vi/$OTHER_ID/hqdefault.jpg",
            ).inOrder()
    }

    @Test
    fun `channel avatars default to the list sized tier`() {
        val raw = "https://yt3.ggpht.com/abc=s512-c-k-c0x00ffffff-no-rj"

        val resolved = ThumbnailUrlResolver.resolveChannelAvatar(raw)

        assertThat(resolved).contains("=s${ThumbnailUrlResolver.AVATAR_SIZE_LIST}")
        assertThat(resolved).doesNotContain("=s512")
    }

    @Test
    fun `channel avatars still honour an explicit larger size`() {
        val raw = "https://yt3.ggpht.com/abc=s88-c-k-c0x00ffffff-no-rj"

        val resolved = ThumbnailUrlResolver.resolveChannelAvatar(raw, size = 512)

        assertThat(resolved).contains("=s512")
    }

    @Test
    fun `avatar resolution is stable so cache keys do not churn`() {
        val raw = "https://yt3.ggpht.com/abc=s512-c-k-c0x00ffffff-no-rj"

        val first = ThumbnailUrlResolver.resolveChannelAvatar(raw)
        val second = ThumbnailUrlResolver.resolveChannelAvatar(first)

        assertThat(second).isEqualTo(first)
    }

    @Test
    fun `non google avatar urls are left untouched`() {
        val raw = "https://example.com/avatar.png"

        assertThat(ThumbnailUrlResolver.resolveChannelAvatar(raw)).isEqualTo(raw)
    }

    private companion object {
        const val VIDEO_ID = "dQw4w9WgXcQ"
        const val OTHER_ID = "aBcDeFgHiJk"
        const val POST_IMAGE_BASE =
            "https://yt3.ggpht.com/ONbcNR93C-Al2A8iKSYb4dkPTUHgd9cR4evpGn5ShzF2JNZasMRpNXyWQr0YaU2-SAsgxfrMj_5RkA"
        const val POST_IMAGE = "$POST_IMAGE_BASE=s1080-c-fcrop64=1,00001ba6ffffe872-rw-nd-v1"
    }

    @Test
    fun `a channel page or a video thumbnail is not a usable avatar`() {
        assertThat(ThumbnailUrlResolver.isUnusableChannelAvatar("https://www.youtube.com/channel/UCkVfrGwV-iG9bSsgCbrNPxQ")).isTrue()
        assertThat(ThumbnailUrlResolver.isUnusableChannelAvatar("https://youtube.com/@betterstack")).isTrue()
        assertThat(ThumbnailUrlResolver.isUnusableChannelAvatar("https://i.ytimg.com/vi/93iYf9wp7Qw/hqdefault.jpg")).isTrue()
        assertThat(ThumbnailUrlResolver.isUnusableChannelAvatar("https://yt3.ggpht.com/abc=s176-c-k-c0x00ffffff-no-rj")).isFalse()
        assertThat(ThumbnailUrlResolver.isUnusableChannelAvatar("")).isFalse()
    }

    @Test
    fun `post image keeps the feed crop by default`() {
        assertThat(ThumbnailUrlResolver.resolveCommunityPostImage(POST_IMAGE))
            .isEqualTo("$POST_IMAGE_BASE=w2048-c-fcrop64=1,00001ba6ffffe872-rw-nd-v1")
    }

    @Test
    fun `uncropped post image drops the crop and every other option`() {
        assertThat(ThumbnailUrlResolver.resolveCommunityPostImage(POST_IMAGE, uncropped = true))
            .isEqualTo("$POST_IMAGE_BASE=w2048")
    }

    @Test
    fun `uncropped post image without options gains only a width`() {
        assertThat(ThumbnailUrlResolver.resolveCommunityPostImage("//yt3.ggpht.com/abc", uncropped = true))
            .isEqualTo("https://yt3.ggpht.com/abc=w2048")
    }

    @Test
    fun `youtube frames replace the uploader's thumbnail tiers at each quality`() {
        fun frames(quality: ThumbnailQuality) =
            ThumbnailUrlResolver.youtubeFrameCandidates("abc123", quality).map { it.substringAfterLast('/') }

        assertThat(frames(ThumbnailQuality.HIGH)).containsExactly("maxres2.jpg", "sd2.jpg", "hq2.jpg").inOrder()
        assertThat(frames(ThumbnailQuality.MEDIUM)).containsExactly("sd2.jpg", "hq2.jpg").inOrder()
        assertThat(frames(ThumbnailQuality.LOW)).containsExactly("mq2.jpg", "hq2.jpg").inOrder()
        assertThat(frames(ThumbnailQuality.OFF)).isEmpty()
    }

    @Test
    fun `a DeArrow frame falls back to youtube frames, never the uploader's thumbnail`() {
        val dearrow = "https://dearrow-thumb.ajay.app/api/v1/getThumbnail?videoID=abc123&time=12.5"

        val candidates =
            ThumbnailUrlResolver.resolveVideoThumbnailCandidates(
                "abc123",
                dearrow,
                ThumbnailQuality.HIGH,
                youTubeFrames = true,
            )

        assertThat(candidates)
            .containsExactly(
                dearrow,
                "https://i.ytimg.com/vi/abc123/maxres2.jpg",
                "https://i.ytimg.com/vi/abc123/sd2.jpg",
                "https://i.ytimg.com/vi/abc123/hq2.jpg",
                "https://i.ytimg.com/vi/abc123/hqdefault.jpg",
            ).inOrder()
    }

    @Test
    fun `the uploader's thumbnail is only the last resort when youtube frames are wanted`() {
        val candidates =
            ThumbnailUrlResolver.resolveVideoThumbnailCandidates(
                "abc123",
                "https://i.ytimg.com/vi/abc123/hq720.jpg",
                ThumbnailQuality.MEDIUM,
                youTubeFrames = true,
            )

        assertThat(candidates)
            .containsExactly(
                "https://i.ytimg.com/vi/abc123/sd2.jpg",
                "https://i.ytimg.com/vi/abc123/hq2.jpg",
                "https://i.ytimg.com/vi/abc123/hqdefault.jpg",
            ).inOrder()
    }

    @Test
    fun `local videos get no youtube frames`() {
        assertThat(ThumbnailUrlResolver.youtubeFrameCandidates("local_42")).isEmpty()
    }
}
