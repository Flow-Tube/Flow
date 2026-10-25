package io.github.aedev.flow.player.stream

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.innertube.models.response.PlayerResponse
import kotlinx.serialization.json.Json
import org.junit.Test

class PlayabilityVerdictTest {
    @Test
    fun `every client reporting ERROR or UNPLAYABLE means the video is gone`() {
        assertThat(
            PlayabilityVerdict.isGone(listOf("ANDROID_VR: status=ERROR, reason=Video unavailable", "IOS: status=UNPLAYABLE, reason=x")),
        ).isTrue()
    }

    @Test
    fun `a web client that served formats means the video exists`() {
        val webOnly =
            listOf(
                "VISIONOS: status=UNPLAYABLE, reason=This video is not available",
                "MWEB: SABR resolved but no format survived decipher/n-transform",
                "ANDROID_VR: status=UNPLAYABLE, reason=This video is not available",
                "MOBILE: 22 formats, none resolvable (SABR-only)",
            )
        val webDemoted =
            listOf(
                "VISIONOS: status=UNPLAYABLE, reason=This video is not available",
                "all SABR clients demoted: MWEB",
                "WEB: SABR resolved but no format survived decipher/n-transform",
                "ANDROID_VR: status=UNPLAYABLE, reason=This video is not available",
            )

        assertThat(PlayabilityVerdict.isGone(webOnly)).isFalse()
        assertThat(PlayabilityVerdict.isGone(webDemoted)).isFalse()
    }

    @Test
    fun `a client skipped without asking leaves a removed video gone`() {
        val reasons =
            listOf(
                "VISIONOS: status=ERROR, reason=Video unavailable",
                "all SABR clients demoted: MWEB, WEB",
                "ANDROID_VR: status=UNPLAYABLE, reason=Video unavailable",
                "TV_TIZEN: status=ERROR, reason=Video unavailable",
            )

        assertThat(PlayabilityVerdict.isGone(reasons)).isTrue()
    }

    @Test
    fun `a bot check, timeout or exception leaves the answer unknown`() {
        assertThat(PlayabilityVerdict.isGone(listOf("WEB: BOT_WALL, reason=Sign in", "IOS: status=ERROR, reason=x"))).isFalse()
        assertThat(PlayabilityVerdict.isGone(listOf("IOS: timeout or null response", "WEB: status=ERROR, reason=x"))).isFalse()
        assertThat(PlayabilityVerdict.isGone(listOf("IOS: exception=IOException: offline"))).isFalse()
    }

    @Test
    fun `a sign-in wall or other status is not treated as gone`() {
        assertThat(PlayabilityVerdict.isGone(listOf("IOS: status=LOGIN_REQUIRED, reason=private"))).isFalse()
        assertThat(PlayabilityVerdict.isGone(listOf("IOS: no adaptive formats"))).isFalse()
        assertThat(PlayabilityVerdict.isGone(emptyList())).isFalse()
    }

    @Test
    fun `a bot check is told apart from an age check`() {
        assertThat(PlayabilityVerdict.isBotWall("Sign in to confirm you’re not a bot")).isTrue()
        assertThat(PlayabilityVerdict.isBotWall("Sign in to confirm you're not a bot")).isTrue()
        assertThat(PlayabilityVerdict.isBotWall("Sign in to confirm your age")).isFalse()
        assertThat(PlayabilityVerdict.isBotWall(null)).isFalse()
    }

    @Test
    fun `a wall on every client that answered blocks the visitor, and the network once a fresh one is walled`() {
        val reasons = listOf("VISIONOS: BOT_WALL, reason=Sign in", "MWEB: timeout or null response")

        assertThat(PlayabilityVerdict.block(reasons)).isEqualTo(PlaybackBlock.BOT_WALL)
        assertThat(PlayabilityVerdict.block(reasons, freshVisitorWalled = true)).isEqualTo(PlaybackBlock.NETWORK_WALL)
        assertThat(PlayabilityVerdict.block(reasons + "IOS: status=ERROR, reason=x")).isNull()
    }

    @Test
    fun `a sign-in status without a bot check needs an account`() {
        val reasons = listOf("VISIONOS: status=LOGIN_REQUIRED, reason=Sign in to confirm your age")

        assertThat(PlayabilityVerdict.block(reasons)).isEqualTo(PlaybackBlock.LOGIN_REQUIRED)
    }

    @Test
    fun `other failures carry no block`() {
        assertThat(PlayabilityVerdict.block(listOf("IOS: status=ERROR, reason=x", "WEB: timeout or null response"))).isNull()
        assertThat(PlayabilityVerdict.block(emptyList())).isNull()
    }

    @Test
    fun `a wall on every asked client is a full wall, one that answered otherwise is not (#1354)`() {
        val walled =
            listOf(
                "ANDROID_VR: BOT_WALL, reason=Sign in to confirm you're not a bot",
                "WEB_EMBEDDED: BOT_WALL, reason=Sign in to confirm you're not a bot",
                "MWEB: PoToken unavailable (WebView missing/broken?)",
                "all SABR clients demoted: WEB",
            )
        assertThat(PlayabilityVerdict.everyClientWalled(walled)).isTrue()
        assertThat(PlayabilityVerdict.everyClientWalled(walled + "IOS: timeout or null response")).isTrue()
        assertThat(PlayabilityVerdict.everyClientWalled(walled + "IOS: status=ERROR, reason=x")).isFalse()
        assertThat(PlayabilityVerdict.everyClientWalled(listOf("MWEB: no visitorData"))).isFalse()
        assertThat(PlayabilityVerdict.everyClientWalled(emptyList())).isFalse()
    }

    @Test
    fun `a song blocked in this country is refused to the viewer, not gone (#1378)`() {
        val reasons =
            listOf(
                "VISIONOS: status=UNPLAYABLE, reason=Video unavailable, " +
                    "subreason=The uploader has not made this video available in your country, details=yes",
                "ANDROID_VR: status=UNPLAYABLE, reason=Video unavailable, " +
                    "subreason=The uploader has not made this video available in your country, details=yes",
                "MWEB: PoToken unavailable (WebView missing/broken?)",
            )

        assertThat(PlayabilityVerdict.isGone(reasons)).isFalse()
        assertThat(PlayabilityVerdict.block(reasons)).isEqualTo(PlaybackBlock.REGION)
    }

    @Test
    fun `an unplayable answer that still carries the video's details is not gone`() {
        val reasons = listOf("VISIONOS: status=UNPLAYABLE, reason=This video is not available, details=yes", "TV_TIZEN: not asked")

        assertThat(PlayabilityVerdict.isGone(reasons)).isFalse()
        assertThat(PlayabilityVerdict.block(reasons)).isNull()
    }

    @Test
    fun `a removed video without details is still gone`() {
        val reasons =
            listOf(
                "VISIONOS: status=ERROR, reason=This video is unavailable",
                "ANDROID_VR: status=UNPLAYABLE, reason=This video is unavailable",
            )

        assertThat(PlayabilityVerdict.isGone(reasons)).isTrue()
    }

    @Test
    fun `a rate limit and a members-only video are refusals of the viewer`() {
        val limited = listOf("VISIONOS: status=ERROR, reason=This content isn't available, try again later.")
        val members = listOf("VISIONOS: status=UNPLAYABLE, reason=Join this channel to get access to members-only content like this video.")

        assertThat(PlayabilityVerdict.isGone(limited)).isFalse()
        assertThat(PlayabilityVerdict.block(limited)).isEqualTo(PlaybackBlock.RATE_LIMITED)
        assertThat(PlayabilityVerdict.isGone(members)).isFalse()
        assertThat(PlayabilityVerdict.block(members)).isEqualTo(PlaybackBlock.MEMBERS_OR_PAID)
    }

    @Test
    fun `a gone video and a country, membership or sign-in refusal stay failed, a wall does not`() {
        assertThat(PlayabilityVerdict.isLasting(isGone = true, block = null)).isTrue()
        listOf(PlaybackBlock.REGION, PlaybackBlock.MEMBERS_OR_PAID, PlaybackBlock.LOGIN_REQUIRED).forEach { block ->
            assertThat(PlayabilityVerdict.isLasting(isGone = false, block = block)).isTrue()
        }
        listOf(PlaybackBlock.BOT_WALL, PlaybackBlock.NETWORK_WALL, PlaybackBlock.RATE_LIMITED, null).forEach { block ->
            assertThat(PlayabilityVerdict.isLasting(isGone = false, block = block)).isFalse()
        }
    }

    @Test
    fun `one answer refuses the viewer only for their country or a membership`() {
        fun refuses(
            status: String,
            reason: String?,
        ) = PlayabilityVerdict.refusesViewer(PlayerResponse.PlayabilityStatus(status = status, reason = reason), hasVideoDetails = true)

        assertThat(refuses("UNPLAYABLE", "The uploader has not made this video available in your country")).isTrue()
        assertThat(refuses("UNPLAYABLE", "Join this channel to get access to members-only content like this video")).isTrue()
        assertThat(refuses("UNPLAYABLE", "Playback on other apps has been disabled by the video owner")).isFalse()
        assertThat(refuses("ERROR", "Video unavailable")).isFalse()
        assertThat(refuses("LOGIN_REQUIRED", "Sign in to confirm your age")).isFalse()
        assertThat(refuses("LOGIN_REQUIRED", "Sign in to confirm you're not a bot")).isFalse()
        assertThat(refuses("UNPLAYABLE", "This content isn't available, try again later")).isFalse()
        assertThat(refuses("OK", null)).isFalse()
    }

    @Test
    fun `a refusal is written with its subreason and whether details came back`() {
        val status =
            Json { ignoreUnknownKeys = true }.decodeFromString<PlayerResponse.PlayabilityStatus>(
                """{"status":"UNPLAYABLE","reason":"Video unavailable","errorScreen":{"playerErrorMessageRenderer":""" +
                    """{"subreason":{"runs":[{"text":"The uploader has not made this video available in your country"}]}}}}""",
            )

        assertThat(PlayabilityVerdict.refusal(status, hasVideoDetails = true)).isEqualTo(
            "status=UNPLAYABLE, reason=Video unavailable, " +
                "subreason=The uploader has not made this video available in your country, details=yes",
        )
        assertThat(PlayabilityVerdict.refusal(status.copy(reason = "Sign in to confirm you're not a bot"), hasVideoDetails = false))
            .isEqualTo("BOT_WALL, reason=Sign in to confirm you're not a bot")
    }
}
