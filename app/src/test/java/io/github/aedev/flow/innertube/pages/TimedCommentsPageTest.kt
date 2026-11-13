package io.github.aedev.flow.innertube.pages

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class TimedCommentsPageTest {
    private fun entity(
        key: String,
        id: String,
        text: String,
        likes: String = "0",
        replies: String = "0",
    ) = """
        {"entityKey": "$key", "payload": {"commentEntityPayload": {
          "key": "$key",
          "properties": {"commentId": "$id", "content": {"content": "$text"}, "publishedTime": "1y ago"},
          "author": {"displayName": "@$id", "channelId": "UC$id"},
          "toolbar": {"likeCountNotliked": "$likes", "replyCount": "$replies"}
        }}}
    """

    private fun response(syncList: String) =
        Json.parseToJsonElement(
            """
            {"frameworkUpdates": {"entityBatchUpdate": {"mutations": [
              ${entity("k-late", "late", "1:05 this is masterpiece", likes = "12", replies = "2")},
              ${entity("k-early", "early", "Perdesi Girl 0:01")},
              {"entityKey": "markers", "payload": {"timedMarkersListSyncEntity": {"key": "markers",
                "timedListData": {"sections": [{"timedSyncDataList": $syncList}]}}}}
            ]}}}
            """.trimIndent(),
        )

    @Test
    fun `reads the list in playback order with YouTube's time for each comment`() {
        val comments =
            response(
                """
                [{"videoTimeMs": "0", "syncItemId": "timed_comments_welcome_sync_id"},
                 {"videoTimeMs": "10", "syncItemId": "timed_comments_welcome_sync_id"},
                 {"videoTimeMs": "1000", "syncItemId": "early"},
                 {"videoTimeMs": "65000", "syncItemId": "late"}]
                """,
            ).toTimedComments(ownVideoId = "vid")

        assertThat(comments.map { it.id }).containsExactly("early", "late").inOrder()
        assertThat(comments.map { it.timedAtMs }).containsExactly(1_000L, 65_000L).inOrder()
        assertThat(comments.last().text).isEqualTo("1:05 this is masterpiece")
        assertThat(comments.last().likeCount).isEqualTo(12)
        assertThat(comments.last().replyCount).isEqualTo(2)
        assertThat(comments.last().continuationToken).isNull()
        assertThat(comments.last().authorChannelId).isEqualTo("UClate")
    }

    @Test
    fun `an entry with no comment behind it is left out`() {
        val comments =
            response(
                """
                [{"videoTimeMs": "1000", "syncItemId": "gone"},
                 {"videoTimeMs": "65000", "syncItemId": "late"},
                 {"videoTimeMs": "70000", "syncItemId": "late"}]
                """,
            ).toTimedComments(ownVideoId = null)

        assertThat(comments.map { it.id }).containsExactly("late")
    }

    @Test
    fun `a response without a timed list has no timed comments`() {
        assertThat(Json.parseToJsonElement("""{"frameworkUpdates": {}}""").toTimedComments(ownVideoId = null)).isEmpty()
    }
}
