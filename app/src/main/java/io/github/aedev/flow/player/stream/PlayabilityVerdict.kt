package io.github.aedev.flow.player.stream

import io.github.aedev.flow.innertube.models.response.PlayerResponse
import java.io.IOException

private val StatusPattern = Regex("""status=(\w+)""")
private val BotWallReason = Regex("""not a bot|confirm you.re""", RegexOption.IGNORE_CASE)
private val RegionReason = Regex("""in your (country|region)""", RegexOption.IGNORE_CASE)
private val RateLimitReason = Regex("""try again later""", RegexOption.IGNORE_CASE)
private val MembersOrPaidReason =
    Regex("""members-only|join this channel|requires payment|premium members""", RegexOption.IGNORE_CASE)
private const val HAS_DETAILS = "details=yes"
private val LastingBlocks = setOf(PlaybackBlock.REGION, PlaybackBlock.MEMBERS_OR_PAID, PlaybackBlock.LOGIN_REQUIRED)

// Clients the ladder skipped without asking say nothing about the video.
private val NotAskedReason = Regex("""demoted|no visitorData|PoToken unavailable|not asked""")

// A client that never answered cannot contradict a wall the others reported.
private val NoAnswerReason = Regex("""timeout or null response|exception=""")

/** Why YouTube refused a video to this viewer, when the refusal is about the viewer and not the video. */
enum class PlaybackBlock {
    /** "Sign in to confirm you're not a bot" on every client: this visitor is flagged. */
    BOT_WALL,

    /** The wall held for a fresh visitor too, so the network is the likelier cause. */
    NETWORK_WALL,

    /** The video needs a signed-in account, for its age or its audience. */
    LOGIN_REQUIRED,

    /** The owner has not made the video available in the viewer's country. */
    REGION,

    /** YouTube is refusing this network for a while: "This content isn't available, try again later". */
    RATE_LIMITED,

    /** The video is for channel members, or has to be bought. */
    MEMBERS_OR_PAID,
}

/** A failed extraction whose [block] explains it better than a generic error. */
class PlaybackBlockedException(
    val block: PlaybackBlock,
) : Exception("YouTube refused playback: $block")

/** Every client said the video itself is gone, so retrying it can only fail the same way. */
class VideoUnavailableException(
    videoId: String,
) : IOException("$videoId is unavailable on every client")

/**
 * Whether a failed extraction means the video itself is gone (removed), as opposed to a failure
 * worth retrying or one about the viewer. A bot check, a timeout or an exception anywhere means the
 * answer is not known, so it is false. Any other reason without a status came from a client that
 * answered OK, so the video exists: label and kids videos are UNPLAYABLE on the app clients but
 * served, ciphered, on the web ones. A removed video comes back without its details; one refused
 * to this viewer (country, rate limit, membership) keeps them or says why, so it is not gone (#1378).
 */
internal object PlayabilityVerdict {
    fun isGone(failureReasons: List<String>): Boolean {
        if (failureReasons.any { "BOT_WALL" in it || "timeout" in it || "exception=" in it }) return false
        val answered = failureReasons.filterNot { NotAskedReason.containsMatchIn(it) }
        return answered.isNotEmpty() && answered.all(::saysGone)
    }

    /** One client's refusal as [isGone] and [block] read it back. Extraction runs in en-US, so the text is English. */
    fun refusal(
        status: PlayerResponse.PlayabilityStatus,
        hasVideoDetails: Boolean,
    ): String {
        val reason = status.reason
        if (isBotWall(reason)) return "BOT_WALL, reason=$reason"
        return buildString {
            append("status=${status.status}, reason=$reason")
            status.subreason?.let { append(", subreason=$it") }
            if (hasVideoDetails) append(", $HAS_DETAILS")
        }
    }

    private fun saysGone(reason: String): Boolean =
        when (StatusPattern.find(reason)?.groupValues?.get(1)) {
            "ERROR" -> viewerBlock(reason) == null
            "UNPLAYABLE" -> HAS_DETAILS !in reason && viewerBlock(reason) == null
            else -> false
        }

    private fun viewerBlock(reason: String): PlaybackBlock? =
        when {
            !StatusPattern.containsMatchIn(reason) -> null
            RegionReason.containsMatchIn(reason) -> PlaybackBlock.REGION
            RateLimitReason.containsMatchIn(reason) -> PlaybackBlock.RATE_LIMITED
            MembersOrPaidReason.containsMatchIn(reason) -> PlaybackBlock.MEMBERS_OR_PAID
            else -> null
        }

    /**
     * Whether a failed play stays failed for this viewer, so the video may be hidden: it is gone, or
     * refused for the viewer's country, a membership, or a sign-in this app cannot offer. Walls and
     * rate limits pass, so they never hide anything.
     */
    fun isLasting(
        isGone: Boolean,
        block: PlaybackBlock?,
    ): Boolean = isGone || block in LastingBlocks

    /**
     * Whether one `/player` answer, asked in English, refuses the video to this viewer for their
     * country or a membership. One client's ERROR is no proof the video is gone, and a plain
     * UNPLAYABLE is how app clients refuse label and kids videos the web clients still serve, so
     * neither counts.
     */
    fun refusesViewer(
        status: PlayerResponse.PlayabilityStatus,
        hasVideoDetails: Boolean,
    ): Boolean =
        when (viewerBlock(refusal(status, hasVideoDetails))) {
            PlaybackBlock.REGION, PlaybackBlock.MEMBERS_OR_PAID -> true
            else -> false
        }

    /** Every client that answered was bot-walled, so the refusal is about this viewer only. */
    fun everyClientWalled(failureReasons: List<String>): Boolean {
        val answered = failureReasons.filterNot { NotAskedReason.containsMatchIn(it) || NoAnswerReason.containsMatchIn(it) }
        return answered.isNotEmpty() && answered.all { "BOT_WALL" in it }
    }

    /** Extraction runs in en-US, so the reason is English; "confirm your age" is not a bot wall. */
    fun isBotWall(reason: String?): Boolean = reason != null && BotWallReason.containsMatchIn(reason)

    /** [freshVisitorWalled]: the wall also held after the visitor was replaced for it. */
    fun block(
        failureReasons: List<String>,
        freshVisitorWalled: Boolean = false,
    ): PlaybackBlock? =
        when {
            everyClientWalled(failureReasons) -> {
                if (freshVisitorWalled) PlaybackBlock.NETWORK_WALL else PlaybackBlock.BOT_WALL
            }

            else -> {
                failureReasons.firstNotNullOfOrNull(::viewerBlock)
                    ?: PlaybackBlock.LOGIN_REQUIRED.takeIf {
                        failureReasons.any { StatusPattern.find(it)?.groupValues?.get(1) == "LOGIN_REQUIRED" }
                    }
            }
        }
}
