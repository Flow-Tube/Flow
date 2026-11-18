package io.github.aedev.flow.player.error

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.R
import io.github.aedev.flow.innertube.models.response.PlayerResponse.PlayabilityStatus
import io.github.aedev.flow.player.stream.PlayabilityVerdict
import io.github.aedev.flow.player.stream.PlaybackBlock
import io.github.aedev.flow.player.stream.PlaybackBlockedException
import io.github.aedev.flow.player.stream.VideoUnavailableException
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

/**
 * What the video screen shows for each refusal YouTube really sends, traced the way a failed load
 * travels: every client's playabilityStatus becomes a failure reason, [PlayabilityVerdict] reads the
 * block and YouTube's own reason from them, PlaybackLoadResolver hands the screen a
 * [PlaybackBlockedException] or [VideoUnavailableException] carrying both, and [VideoErrorMapper]
 * names it.
 *
 * The statuses and reasons are the ones ANDROID_VR, IOS and WEB returned on 2026-11-17 for real
 * videos (ids in notes/newpipe-parity). Before this, private and age-restricted videos both said
 * "sign-in required", Music Premium said "members-only or paid", and every removal said "failed to
 * load video".
 */
class YouTubeRefusalMessageTest {
    private val context =
        mockk<Context> {
            every { getString(any()) } answers { "s:${firstArg<Int>()}" }
            every { getString(any(), *anyVararg()) } answers { "s:${firstArg<Int>()}:${secondArg<Array<Any>>().joinToString()}" }
        }

    private class Answer(
        val client: String,
        val status: String,
        val reason: String,
        val hasDetails: Boolean,
        val messages: List<String>? = null,
    )

    private data class Outcome(
        val message: String,
        val hint: String?,
        val gone: Boolean,
        val block: PlaybackBlock?,
    )

    private fun native(vararg answers: Answer): Outcome {
        val reasons =
            answers.map {
                "${it.client}: ${PlayabilityVerdict.refusal(
                    PlayabilityStatus(it.status, it.reason, messages = it.messages),
                    it.hasDetails,
                )}"
            }
        val block = PlayabilityVerdict.block(reasons)
        val gone = PlayabilityVerdict.isGone(reasons)
        val refusal = PlayabilityVerdict.refusalOf(reasons)
        val cause =
            when {
                block != null -> PlaybackBlockedException(block, refusal)
                gone || refusal != null -> VideoUnavailableException("id", refusal)
                else -> null
            }
        val shown = VideoErrorMapper.from(context, cause)
        return Outcome(shown.message, shown.hint, gone, block)
    }

    private fun s(id: Int) = "s:$id"

    private fun vr(
        status: String,
        reason: String,
        details: Boolean,
    ) = Answer("ANDROID_VR", status, reason, details)

    private fun ios(
        status: String,
        reason: String,
        details: Boolean,
    ) = Answer("IOS", status, reason, details)

    private fun web(
        status: String,
        reason: String,
        details: Boolean,
    ) = Answer("WEB", status, reason, details)

    private val privateMessage = listOf("This is a private video. Please sign in to verify that you may see it.")
    private val privateVideo =
        listOf(
            Answer("ANDROID_VR", "LOGIN_REQUIRED", "Please sign in", false, privateMessage),
            ios("LOGIN_REQUIRED", "This video is private", false),
            web("LOGIN_REQUIRED", "Private video", false),
        )
    private val ageRestricted =
        listOf(
            vr("LOGIN_REQUIRED", "Sign in to confirm your age", true),
            ios("LOGIN_REQUIRED", "This video may be inappropriate for some users.", true),
            web("LOGIN_REQUIRED", "Sign in to confirm your age", true),
        )
    private val membersOnly =
        listOf(
            vr(
                "UNPLAYABLE",
                "This video is available to this channel's members on level: Magnificent Members (or any higher level). " +
                    "Join this YouTube channel from your computer or mobile app.",
                true,
            ),
            ios(
                "UNPLAYABLE",
                "This video is available to this channel's members on level: Magnificent Members (or any higher level). " +
                    "Join this channel to get access to members-only content and other exclusive perks.",
                true,
            ),
        )
    private val musicPremium =
        listOf(
            vr("UNPLAYABLE", "This video is only available to Music Premium members", true),
            ios("UNPLAYABLE", "This video is only available to Music Premium members", true),
        )
    private val accountTerminated =
        listOf(
            vr(
                "ERROR",
                "This video is no longer available because the YouTube account associated with this video has been terminated.",
                false,
            ),
            ios(
                "ERROR",
                "This video is no longer available because the YouTube account associated with this video has been terminated.",
                false,
            ),
            web("ERROR", "Video unavailable", false),
        )
    private val copyrightRemoval =
        listOf(
            vr("ERROR", "It was removed following a copyright removal request by Nonami Docs", false),
            ios("ERROR", "It was removed following a copyright removal request by Nonami Docs", false),
            web("ERROR", "Video unavailable", false),
        )
    private val removedByUploader =
        listOf(
            vr("ERROR", "This video has been removed by the uploader", false),
            ios("ERROR", "This video has been removed by the uploader", false),
        )
    private val communityGuidelines =
        listOf(
            vr("ERROR", "This video has been removed for violating YouTube's Community Guidelines", false),
            ios("ERROR", "This video has been removed for violating YouTube's Community Guidelines", false),
        )
    private val nuditySexualContent =
        listOf(
            vr("ERROR", "This video has been removed for violating YouTube's policy on nudity or sexual content", false),
            ios("ERROR", "This video has been removed for violating YouTube's policy on nudity or sexual content", false),
        )
    private val hateSpeech =
        listOf(
            vr(
                "ERROR",
                "This video has been removed for violating YouTube's policy on hate speech. " +
                    "Learn more about combating hate speech in your country.",
                false,
            ),
            ios(
                "ERROR",
                "This video has been removed for violating YouTube's policy on hate speech. " +
                    "Learn more about combating hate speech in your country.",
                false,
            ),
        )
    private val unavailable =
        listOf(
            vr("ERROR", "This video is unavailable", false),
            ios("ERROR", "This video is unavailable", false),
        )
    private val liveRecordingGone =
        listOf(
            vr("UNPLAYABLE", "This live stream recording is not available.", true),
            ios("UNPLAYABLE", "This live stream recording is not available.", true),
        )

    private fun outcome(
        message: Int,
        hint: String,
        gone: Boolean,
        block: PlaybackBlock?,
    ) = Outcome(s(message), hint, gone, block)

    @Test
    fun `a private video says it is private`() {
        assertThat(native(*privateVideo.toTypedArray()))
            .isEqualTo(outcome(R.string.error_private_video, s(R.string.error_private_video_hint), false, PlaybackBlock.LOGIN_REQUIRED))
        // ANDROID_VR alone says only "Please sign in"; its message still names the video private.
        assertThat(native(privateVideo.first()).message).isEqualTo(s(R.string.error_private_video))
    }

    @Test
    fun `an age restricted video says it is age-restricted`() {
        assertThat(native(*ageRestricted.toTypedArray()))
            .isEqualTo(outcome(R.string.error_age_restricted, s(R.string.error_age_restricted_hint), false, PlaybackBlock.LOGIN_REQUIRED))
        assertThat(native(ageRestricted[1]).message).isEqualTo(s(R.string.error_age_restricted))
    }

    @Test
    fun `a members-only video says members-only, from any client's wording`() {
        val expected = outcome(R.string.error_members_only, s(R.string.error_members_only_hint), false, PlaybackBlock.MEMBERS_OR_PAID)
        assertThat(native(*membersOnly.toTypedArray())).isEqualTo(expected)
        assertThat(native(membersOnly.first())).isEqualTo(expected)
    }

    @Test
    fun `a Music Premium video names Music Premium`() {
        assertThat(native(*musicPremium.toTypedArray()))
            .isEqualTo(
                outcome(R.string.error_yt_music_premium, s(R.string.error_yt_music_premium_hint), false, PlaybackBlock.MEMBERS_OR_PAID),
            )
    }

    @Test
    fun `each removal says why YouTube removed it`() {
        assertThat(native(*accountTerminated.toTypedArray()))
            .isEqualTo(outcome(R.string.error_channel_terminated, s(R.string.error_channel_terminated_hint), true, null))
        assertThat(native(*copyrightRemoval.toTypedArray()))
            .isEqualTo(outcome(R.string.error_copyright_removed, "s:${R.string.error_copyright_removed_hint}:Nonami Docs", true, null))
        assertThat(native(*removedByUploader.toTypedArray()))
            .isEqualTo(outcome(R.string.error_removed_by_uploader, s(R.string.error_removed_by_uploader_hint), true, null))
        assertThat(native(*communityGuidelines.toTypedArray()))
            .isEqualTo(outcome(R.string.error_policy_removed, s(R.string.error_policy_removed_hint), true, null))
        assertThat(native(*nuditySexualContent.toTypedArray()))
            .isEqualTo(outcome(R.string.error_policy_removed, s(R.string.error_policy_removed_hint), true, null))
        assertThat(native(*unavailable.toTypedArray()))
            .isEqualTo(outcome(R.string.error_not_available, s(R.string.error_not_available_hint), true, null))
    }

    @Test
    fun `a video removed for hate speech is removed, not region blocked`() {
        assertThat(native(*hateSpeech.toTypedArray()))
            .isEqualTo(outcome(R.string.error_policy_removed, s(R.string.error_policy_removed_hint), true, null))
    }

    @Test
    fun `a live stream whose recording is gone says the recording is not available`() {
        assertThat(native(*liveRecordingGone.toTypedArray()))
            .isEqualTo(outcome(R.string.error_recording_unavailable, s(R.string.error_recording_unavailable_hint), false, null))
    }

    @Test
    fun `a region block that another client calls unavailable still names the region`() {
        val outcome =
            native(
                vr("UNPLAYABLE", "The uploader has not made this video available in your country", true),
                web("ERROR", "Video unavailable", false),
            )
        assertThat(outcome.message).isEqualTo(s(R.string.error_geo_restricted))
        assertThat(outcome.block).isEqualTo(PlaybackBlock.REGION)
    }
}
