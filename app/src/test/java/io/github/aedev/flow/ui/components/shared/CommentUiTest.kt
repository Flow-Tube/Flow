package io.github.aedev.flow.ui.components.shared

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.data.model.RichText
import io.github.aedev.flow.data.model.RichTextSpan
import io.github.aedev.flow.data.model.RichTextTarget
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Pins the comment row after the dead controls came out of it: one engagement icon, the creator's
 * badges, and the chips that pick an order rather than re-sorting what is loaded.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
class CommentUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun string(id: Int) = context.getString(id)

    private fun comment(
        isHearted: Boolean = false,
        isCreator: Boolean = false,
        isVerified: Boolean = false,
        likeCountText: String = "529K",
        pinnedByText: String? = null,
    ) = Comment(
        id = "c1",
        author = "@Queen",
        authorThumbnail = "",
        text = "Watch 1:57 for the solo",
        likeCount = 529_000,
        publishedTime = "7 years ago",
        replyCount = 0,
        isPinned = pinnedByText != null,
        likeCountText = likeCountText,
        pinnedByText = pinnedByText,
        isHearted = isHearted,
        heartedByText = "by @Queen".takeIf { isHearted },
        isVerified = isVerified,
        isCreator = isCreator,
        richText =
            RichText(
                text = "Watch 1:57 for the solo",
                spans = listOf(RichTextSpan(start = 6, length = 4, target = RichTextTarget.Timestamp(117L))),
            ),
    )

    private fun setItem(
        comment: Comment,
        onSeekMs: (Long) -> Unit = {},
    ) {
        rule.setContent {
            MaterialTheme {
                FlowCommentItem(
                    comment = comment,
                    onSeekMs = onSeekMs,
                    onLoadReplies = {},
                    onLoadMoreReplies = {},
                    isRepliesVisible = false,
                    onToggleReplies = {},
                    isExpanded = false,
                    onExpand = {},
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun theRowShowsOneEngagementIconAndNoDislikeOrReply() {
        setItem(comment())

        rule.onNodeWithContentDescription(string(R.string.like)).assertIsDisplayed()
        assertThat(rule.onAllNodesWithContentDescription(string(R.string.player_action_dislike)).fetchSemanticsNodes())
            .isEmpty()
        assertThat(rule.onAllNodesWithContentDescription(string(R.string.reply)).fetchSemanticsNodes())
            .isEmpty()
    }

    @Test
    fun theLikeCountIsTheOneTheServerPrinted() {
        setItem(comment(likeCountText = "529K"))

        rule.onNodeWithText("529K").assertIsDisplayed()
    }

    @Test
    fun aHeartedCommentShowsTheCreatorHeartWithItsTooltip() {
        setItem(comment(isHearted = true))

        rule.onNodeWithContentDescription("by @Queen").assertIsDisplayed()
    }

    @Test
    fun anUnheartedCommentShowsNoHeart() {
        setItem(comment(isHearted = false))

        assertThat(rule.onAllNodesWithContentDescription(string(R.string.comment_hearted_by_creator)).fetchSemanticsNodes())
            .isEmpty()
    }

    @Test
    fun aVerifiedAuthorShowsTheBadge() {
        setItem(comment(isVerified = true))

        rule.onNodeWithContentDescription(string(R.string.verified)).assertIsDisplayed()
    }

    @Test
    fun aPinnedCommentShowsTheServersOwnPinnedText() {
        setItem(comment(pinnedByText = "Pinned by @Queen"))

        rule.onNodeWithText("Pinned by @Queen").assertIsDisplayed()
    }

    @Test
    fun theChipsOfferEveryOrderIncludingTimed() {
        var selected = CommentSortFilter.TOP
        rule.setContent {
            MaterialTheme {
                CommentSortChips(
                    selected = selected,
                    onSelect = { selected = it },
                    showTimed = true,
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText(string(R.string.filter_top)).assertIsSelected()
        rule.onNodeWithText(string(R.string.filter_newest)).performClick()
        assertThat(selected).isEqualTo(CommentSortFilter.NEWEST)

        rule.onNodeWithText(string(R.string.filter_oldest)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.filter_timed)).performClick()
        assertThat(selected).isEqualTo(CommentSortFilter.TIMED)
    }

    @Test
    fun theTimedChipIsHiddenWhereTimestampsCannotExist() {
        rule.setContent {
            MaterialTheme {
                CommentSortChips(
                    selected = CommentSortFilter.TOP,
                    onSelect = {},
                )
            }
        }
        rule.waitForIdle()

        assertThat(rule.onAllNodesWithContentDescription(string(R.string.filter_timed)).fetchSemanticsNodes())
            .isEmpty()
    }
}
