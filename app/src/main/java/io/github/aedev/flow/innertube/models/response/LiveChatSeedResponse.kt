package io.github.aedev.flow.innertube.models.response

import kotlinx.serialization.Serializable

@Serializable
data class LiveChatSeedResponse(
    val contents: Contents? = null,
) {
    @Serializable
    data class Contents(
        val twoColumnWatchNextResults: TwoColumnWatchNextResults? = null,
    ) {
        @Serializable
        data class TwoColumnWatchNextResults(
            val conversationBar: ConversationBar? = null,
        ) {
            @Serializable
            data class ConversationBar(
                val liveChatRenderer: LiveChatRenderer? = null,
            ) {
                @Serializable
                data class LiveChatRenderer(
                    val continuations: List<Continuation> = emptyList(),
                    val isReplay: Boolean? = null,
                ) {
                    @Serializable
                    data class Continuation(
                        val reloadContinuationData: ReloadContinuationData? = null,
                    ) {
                        @Serializable
                        data class ReloadContinuationData(
                            val continuation: String? = null,
                        )
                    }
                }
            }
        }
    }

    // The seed continuation token
    fun seedContinuation(): String? =
        contents
            ?.twoColumnWatchNextResults
            ?.conversationBar
            ?.liveChatRenderer
            ?.continuations
            ?.firstOrNull()
            ?.reloadContinuationData
            ?.continuation

    /** The seed of a finished stream's chat; null for a live one, or when replay is off. */
    fun replayContinuation(): String? =
        seedContinuation()?.takeIf {
            contents
                ?.twoColumnWatchNextResults
                ?.conversationBar
                ?.liveChatRenderer
                ?.isReplay == true
        }
}
