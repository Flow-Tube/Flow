package io.github.aedev.flow.innertube.models.response

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class ChatReplaySeedTest {
    private fun watch(liveChatRenderer: String?): String {
        val bar = liveChatRenderer?.let { """"conversationBar": {"liveChatRenderer": $it},""" }.orEmpty()
        return """{"contents": {"twoColumnWatchNextResults": {$bar "results": {}}}}"""
    }

    private fun token(body: String) = ChatReplaySeed.token(Json.parseToJsonElement(body))

    @Test
    fun `a finished stream's watch response carries the replay seed`() {
        val body =
            watch(
                """{"isReplay": true, "continuations": [{"reloadContinuationData": {"continuation": "seed-1"}}]}""",
            )

        assertThat(token(body)).isEqualTo("seed-1")
    }

    @Test
    fun `a stream that is still live is not a replay`() {
        val body = watch("""{"continuations": [{"reloadContinuationData": {"continuation": "live"}}]}""")

        assertThat(token(body)).isNull()
    }

    @Test
    fun `a video with replay turned off or no chat has no seed`() {
        assertThat(token(watch(null))).isNull()
        assertThat(token(watch("""{"isReplay": true, "continuations": []}"""))).isNull()
        assertThat(ChatReplaySeed.token(null)).isNull()
    }
}
