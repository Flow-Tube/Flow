package io.github.aedev.flow.player.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cases are the real shapes observed from the InnerTube `player` endpoint, not invented ones —
 * every VISIONOS response carries an HLS manifest, which is what made the naive rule misfire.
 */
class LiveDetectionRulesTest {
    private fun rule(
        isLive: Boolean? = null,
        isPostLiveDvr: Boolean? = null,
        hasLiveStreamability: Boolean = false,
        hasHlsManifest: Boolean = false,
        hasAdaptiveFormats: Boolean = false,
    ) = LiveDetectionRules.isLiveNow(
        isLive = isLive,
        isPostLiveDvr = isPostLiveDvr,
        hasLiveStreamability = hasLiveStreamability,
        hasHlsManifest = hasHlsManifest,
        hasAdaptiveFormats = hasAdaptiveFormats,
    )

    @Test
    fun `visionos VOD ships an hls manifest and is not live`() {
        // The regression: 98 adaptive formats alongside a manifest. Reading this as live returns an
        // empty ladder, so codecs and audio tracks vanish from the UI.
        assertFalse(rule(hasHlsManifest = true, hasAdaptiveFormats = true))
    }

    @Test
    fun `visionos short ships an hls manifest and is not live`() {
        assertFalse(rule(hasHlsManifest = true, hasAdaptiveFormats = true))
    }

    @Test
    fun `a broadcasting stream is live`() {
        assertTrue(rule(isLive = true, hasLiveStreamability = true, hasHlsManifest = true, hasAdaptiveFormats = true))
    }

    @Test
    fun `a post-live dvr stream is live even with an adaptive ladder`() {
        assertTrue(rule(isPostLiveDvr = true, hasHlsManifest = true, hasAdaptiveFormats = true))
    }

    @Test
    fun `live streamability alone is enough`() {
        assertTrue(rule(hasLiveStreamability = true))
    }

    @Test
    fun `a manifest with nothing else playable is still treated as live`() {
        // The clause's original purpose, preserved: SABR-only live responses expose no adaptive
        // formats, so the manifest is the only thing there is to play.
        assertTrue(rule(hasHlsManifest = true, hasAdaptiveFormats = false))
    }

    @Test
    fun `a plain VOD with neither manifest nor live flags is not live`() {
        assertFalse(rule(hasAdaptiveFormats = true))
    }

    @Test
    fun `a manifest signed for a fixed stretch has a capped dvr window`() {
        val url = "https://manifest.googlevideo.com/api/manifest/hls_variant/playlist_duration/3600/file/index.m3u8"
        assertTrue(LiveDetectionRules.hasCappedDvrWindow(url))
    }

    @Test
    fun `a manifest with no stretch in its url keeps the whole dvr window`() {
        val url = "https://manifest.googlevideo.com/api/manifest/hls_variant/hfr/1/ss/1/playlist_type/DVR/file/index.m3u8"
        assertFalse(LiveDetectionRules.hasCappedDvrWindow(url))
    }

    @Test
    fun `no manifest has no capped dvr window`() {
        assertFalse(LiveDetectionRules.hasCappedDvrWindow(null))
    }

    @Test
    fun `an uncapped hls manifest is dropped for the dash one`() {
        val manifests = LiveDetectionRules.playableLiveManifests(UNCAPPED_HLS, DASH)
        assertEquals(LiveDetectionRules.LiveManifests(hlsUrl = null, dashUrl = DASH), manifests)
    }

    @Test
    fun `a capped hls manifest is kept`() {
        val manifests = LiveDetectionRules.playableLiveManifests(CAPPED_HLS, null)
        assertEquals(LiveDetectionRules.LiveManifests(hlsUrl = CAPPED_HLS, dashUrl = null), manifests)
    }

    @Test
    fun `an uncapped hls manifest with no dash is kept`() {
        val manifests = LiveDetectionRules.playableLiveManifests(UNCAPPED_HLS, null)
        assertEquals(LiveDetectionRules.LiveManifests(hlsUrl = UNCAPPED_HLS, dashUrl = null), manifests)
    }

    @Test
    fun `a dash manifest alone is kept`() {
        val manifests = LiveDetectionRules.playableLiveManifests(null, DASH)
        assertEquals(LiveDetectionRules.LiveManifests(hlsUrl = null, dashUrl = DASH), manifests)
    }

    private companion object {
        const val CAPPED_HLS = "https://manifest.googlevideo.com/api/manifest/hls_variant/playlist_duration/3600/file/index.m3u8"
        const val UNCAPPED_HLS = "https://manifest.googlevideo.com/api/manifest/hls_variant/playlist_type/DVR/file/index.m3u8"
        const val DASH = "https://manifest.googlevideo.com/api/manifest/dash/source/yt_live_broadcast/file/default.mpd"
    }
}
