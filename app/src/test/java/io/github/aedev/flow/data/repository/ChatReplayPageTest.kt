package io.github.aedev.flow.data.repository

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.innertube.models.response.GetLiveChatResponse
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.junit.Test

class ChatReplayPageTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun text(
        id: String,
        body: String,
        time: String,
    ) = """{"addChatItemAction": {"item": {"liveChatTextMessageRenderer": {
        "id": "$id", "message": {"runs": [{"text": "$body"}]}, "authorName": {"simpleText": "@viewer"},
        "timestampText": {"simpleText": "$time"}}}}}"""

    private val response =
        """
        {"continuationContents": {"liveChatContinuation": {
          "continuations": [
            {"liveChatReplayContinuationData": {"continuation": "next-1", "timeUntilLastMessageMsec": 5000}},
            {"playerSeekContinuationData": {"continuation": "seek-1"}}
          ],
          "actions": [
            {"replayChatItemAction": {"videoOffsetTimeMsec": "0", "actions": [
              {"addChatItemAction": {"item": {"liveChatViewerEngagementMessageRenderer": {"id": "banner"}}}}
            ]}},
            {"replayChatItemAction": {"videoOffsetTimeMsec": "1500", "actions": [${text("a", "hello", "0:01")}]}},
            {"replayChatItemAction": {"videoOffsetTimeMsec": "28000", "actions": [${text("b", "liftoff", "0:28")}]}}
          ]
        }}}
        """.trimIndent()

    @Test
    fun `replay actions map to messages with their time in the video`() {
        val continuation =
            json
                .decodeFromString<GetLiveChatResponse>(response)
                .continuationContents!!
                .liveChatContinuation!!

        val page = LiveChatRepository(mockk(relaxed = true)).replayPageOf(continuation)

        assertThat(page.messages.map { it.offsetMs to it.message.id }).containsExactly(1_500L to "a", 28_000L to "b").inOrder()
        assertThat(
            page.messages
                .first()
                .message.message,
        ).isEqualTo("hello")
        assertThat(
            page.messages
                .first()
                .message.timestamp,
        ).isEqualTo("0:01")
        assertThat(page.nextContinuation).isEqualTo("next-1")
        assertThat(page.seekContinuation).isEqualTo("seek-1")
    }

    @Test
    fun `the last page has no next token`() {
        val continuation = GetLiveChatResponse.LiveChatContinuation()

        val page = LiveChatRepository(mockk(relaxed = true)).replayPageOf(continuation)

        assertThat(page.messages).isEmpty()
        assertThat(page.nextContinuation).isNull()
        assertThat(page.seekContinuation).isNull()
    }
}
