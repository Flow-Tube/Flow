package io.github.aedev.flow.innertube.models.response

import kotlinx.serialization.Serializable

/**
 * Response for the InnerTube `live_chat/get_live_chat` endpoint. Models text, super-chat (paid)
 * and membership messages plus the next continuation token + server-recommended poll interval.
 * All fields default so partial/unknown payloads never throw
 */
@Serializable
data class GetLiveChatResponse(
    val continuationContents: ContinuationContents? = null,
) {
    @Serializable
    data class ContinuationContents(
        val liveChatContinuation: LiveChatContinuation? = null,
    )

    @Serializable
    data class LiveChatContinuation(
        val continuations: List<Continuation> = emptyList(),
        val actions: List<Action> = emptyList(),
    )

    @Serializable
    data class Continuation(
        val timedContinuationData: ContinuationData? = null,
        val invalidationContinuationData: ContinuationData? = null,
        val reloadContinuationData: ContinuationData? = null,
        val liveChatReplayContinuationData: ContinuationData? = null,
        val playerSeekContinuationData: ContinuationData? = null,
    ) {
        @Serializable
        data class ContinuationData(
            val continuation: String? = null,
            val timeoutMs: Long? = null,
        )

        fun token(): String? =
            timedContinuationData?.continuation
                ?: invalidationContinuationData?.continuation
                ?: reloadContinuationData?.continuation

        fun timeoutMs(): Long? = timedContinuationData?.timeoutMs ?: invalidationContinuationData?.timeoutMs
    }

    @Serializable
    data class Action(
        val addChatItemAction: AddChatItemAction? = null,
        val replayChatItemAction: ReplayChatItemAction? = null,
    ) {
        @Serializable
        data class ReplayChatItemAction(
            val actions: List<Action> = emptyList(),
            val videoOffsetTimeMsec: String? = null,
        )

        @Serializable
        data class AddChatItemAction(
            val item: Item? = null,
        )

        @Serializable
        data class Item(
            val liveChatTextMessageRenderer: ChatMessageRenderer? = null,
            val liveChatPaidMessageRenderer: ChatMessageRenderer? = null,
            val liveChatMembershipItemRenderer: ChatMessageRenderer? = null,
        )
    }

    // One renderer shape covering text / paid / membership messages
    @Serializable
    data class ChatMessageRenderer(
        val id: String? = null,
        val message: Message? = null,
        val headerSubtext: Message? = null,
        val authorName: Text? = null,
        val authorPhoto: ChatImage? = null,
        val timestampText: Text? = null,
        val authorBadges: List<AuthorBadge> = emptyList(),
        // Super-chat only:
        val purchaseAmountText: Text? = null,
        val bodyBackgroundColor: Long? = null,
        val headerBackgroundColor: Long? = null,
    )

    @Serializable
    data class Message(
        val runs: List<Run> = emptyList(),
    ) {
        @Serializable
        data class Run(
            val text: String? = null,
            val emoji: Emoji? = null,
        ) {
            @Serializable
            data class Emoji(
                val emojiId: String? = null,
                val shortcuts: List<String> = emptyList(),
                val isCustomEmoji: Boolean? = null,
                val image: ChatImage? = null,
            )

            fun displayText(): String =
                when {
                    text != null -> {
                        text
                    }

                    emoji != null -> {
                        val unicode = emoji.emojiId?.takeIf { emoji.isCustomEmoji != true }
                        unicode ?: emoji.shortcuts.firstOrNull() ?: emoji.emojiId ?: ""
                    }

                    else -> {
                        ""
                    }
                }
        }

        fun plainText(): String = runs.joinToString("") { it.displayText() }
    }

    @Serializable
    data class Text(
        val simpleText: String? = null,
        val runs: List<Message.Run> = emptyList(),
    ) {
        fun value(): String? = simpleText ?: runs.joinToString("") { it.displayText() }.takeIf { it.isNotEmpty() }
    }

    @Serializable
    data class ChatImage(
        val thumbnails: List<Thumb> = emptyList(),
    ) {
        @Serializable
        data class Thumb(
            val url: String? = null,
            val width: Int? = null,
            val height: Int? = null,
        )

        fun bestUrl(): String? = thumbnails.maxByOrNull { it.height ?: 0 }?.url
    }

    @Serializable
    data class AuthorBadge(
        val liveChatAuthorBadgeRenderer: Renderer? = null,
    ) {
        @Serializable
        data class Renderer(
            val tooltip: String? = null,
            val icon: Icon? = null,
            val customThumbnail: ChatImage? = null,
        ) {
            @Serializable
            data class Icon(
                val iconType: String? = null,
            )
        }
    }
}
