package io.github.aedev.flow.player.error

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.R
import io.github.aedev.flow.innertube.models.response.PlayerResponse.PlayabilityStatus
import io.github.aedev.flow.player.stream.PlayabilityVerdict
import io.github.aedev.flow.player.stream.PlaybackBlock
import io.github.aedev.flow.player.stream.PlaybackBlockedException
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

/**
 * What the video screen shows today for each refusal YouTube really sends, traced the way a failed
 * load travels: every client's playabilityStatus becomes a failure reason, [PlayabilityVerdict]
 * reads the reasons, and the screen maps `blockOf(videoId)?.let(::PlaybackBlockedException)` (the
 * cause PlaybackLoadResolver hands over) through [VideoErrorMapper].
 *
 * The statuses and reasons are the ones ANDROID_VR, IOS and WEB returned on 2026-11-17 for real
 * videos (ids in notes/newpipe-parity). The NewPipe column is what NewPipe 0.26.5's own reading of
 * the same answer would have shown, which is what the screen showed while NewPipe extracted.
 */
class YouTubeRefusalMessageTest {
    private val context =
        mockk<Context> {
            every { getString(any()) } answers { "s:${firstArg<Int>()}" }
        }

    private class Answer(
        val client: String,
        val status: String,
        val reason: String,
        val hasDetails: Boolean,
    )

    private data class Outcome(
        val message: String,
        val gone: Boolean,
        val block: PlaybackBlock?,
    )

    private fun native(vararg answers: Answer): Outcome {
        val reasons =
            answers.map { "${it.client}: ${PlayabilityVerdict.refusal(PlayabilityStatus(it.status, it.reason), it.hasDetails)}" }
        val block = PlayabilityVerdict.block(reasons)
        val shown = VideoErrorMapper.from(context, block?.let(::PlaybackBlockedException))
        return Outcome(shown.message, PlayabilityVerdict.isGone(reasons), block)
    }

    private fun newPipe(answer: Answer): String =
        VideoErrorMapper.from(context, NewPipeErrorCases.newPipeVerdict(answer.status, answer.reason)).message

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

    private val privateVideo =
        listOf(
            vr("LOGIN_REQUIRED", "Please sign in", false),
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

    @Test
    fun `a private video shows the sign-in message`() {
        assertThat(native(*privateVideo.toTypedArray()))
            .isEqualTo(Outcome(s(R.string.error_login_required), gone = false, block = PlaybackBlock.LOGIN_REQUIRED))
    }

    @Test
    fun `an age restricted video shows the sign-in message`() {
        assertThat(native(*ageRestricted.toTypedArray()))
            .isEqualTo(Outcome(s(R.string.error_login_required), gone = false, block = PlaybackBlock.LOGIN_REQUIRED))
    }

    @Test
    fun `a members-only video shows the members message once a client says members-only`() {
        assertThat(native(*membersOnly.toTypedArray()))
            .isEqualTo(Outcome(s(R.string.error_members_or_paid), gone = false, block = PlaybackBlock.MEMBERS_OR_PAID))
    }

    @Test
    fun `a members-only video refused by ANDROID_VR alone is not recognised and shows the generic message`() {
        assertThat(native(membersOnly.first()))
            .isEqualTo(Outcome(s(R.string.error_generic), gone = false, block = null))
    }

    @Test
    fun `a Music Premium video shows the members message`() {
        assertThat(native(*musicPremium.toTypedArray()))
            .isEqualTo(Outcome(s(R.string.error_members_or_paid), gone = false, block = PlaybackBlock.MEMBERS_OR_PAID))
    }

    @Test
    fun `removed videos are gone and show the generic message`() {
        for (refusal in listOf(
            accountTerminated,
            copyrightRemoval,
            removedByUploader,
            communityGuidelines,
            nuditySexualContent,
            unavailable,
        )) {
            assertThat(native(*refusal.toTypedArray()))
                .isEqualTo(Outcome(s(R.string.error_generic), gone = true, block = null))
        }
    }

    @Test
    fun `a video removed for hate speech reads as region blocked because its reason says in your country`() {
        assertThat(native(*hateSpeech.toTypedArray()))
            .isEqualTo(Outcome(s(R.string.error_geo_restricted), gone = false, block = PlaybackBlock.REGION))
    }

    @Test
    fun `a live stream whose recording is gone is not gone and shows the generic message`() {
        assertThat(native(*liveRecordingGone.toTypedArray()))
            .isEqualTo(Outcome(s(R.string.error_generic), gone = false, block = null))
    }

    @Test
    fun `NewPipe read each refusal by its English reason, so the client decided the message`() {
        assertThat(newPipe(privateVideo[0])).isEqualTo(s(R.string.error_not_available))
        assertThat(newPipe(privateVideo[1])).isEqualTo(s(R.string.error_private_video))
        assertThat(newPipe(privateVideo[2])).isEqualTo(s(R.string.error_not_available))

        assertThat(newPipe(ageRestricted[0])).isEqualTo(s(R.string.error_not_available))
        assertThat(newPipe(ageRestricted[1])).isEqualTo(s(R.string.error_age_restricted))

        assertThat(newPipe(membersOnly[0])).isEqualTo(s(R.string.error_paid_content))
        assertThat(newPipe(musicPremium[0])).isEqualTo(s(R.string.error_yt_music_premium))

        assertThat(newPipe(accountTerminated[0])).isEqualTo(s(R.string.error_channel_terminated))
        assertThat(newPipe(accountTerminated[2])).isEqualTo(s(R.string.error_not_available))
        assertThat(newPipe(copyrightRemoval[0])).isEqualTo(s(R.string.error_not_available))
        assertThat(newPipe(removedByUploader[0])).isEqualTo(s(R.string.error_not_available))
        assertThat(newPipe(communityGuidelines[0])).isEqualTo(s(R.string.error_not_available))
        assertThat(newPipe(hateSpeech[0])).isEqualTo(s(R.string.error_geo_restricted))
        assertThat(newPipe(unavailable[0])).isEqualTo(s(R.string.error_not_available))
        assertThat(newPipe(liveRecordingGone[0])).isEqualTo(s(R.string.error_not_available))
    }
}
