package io.github.aedev.flow.data.repository

import android.util.Log
import io.github.aedev.flow.data.model.LiveChatMessage
import io.github.aedev.flow.data.model.LiveChatMessageType
import io.github.aedev.flow.data.model.LiveChatSegment
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.models.response.ChatReplaySeed
import io.github.aedev.flow.innertube.models.response.GetLiveChatResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches YouTube live chat via the native InnerTube stack (no NewPipeExtractor). Ported from PipePipe's
 * YoutubeBulletCommentsExtractor
 */
@Singleton
class LiveChatRepository
    @Inject
    constructor(
        private val youTubeRepository: YouTubeRepository,
    ) {
        data class LiveChatPage(
            val messages: List<LiveChatMessage>,
            val nextContinuation: String?,
            val timeoutMs: Long,
        )

        suspend fun initialContinuation(videoId: String): String? =
            withContext(Dispatchers.IO) {
                try {
                    ChatReplaySeed.liveToken(youTubeRepository.watchNextResponse(videoId))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to seed live chat continuation for $videoId: ${e.message}")
                    null
                }
            }

        suspend fun poll(continuation: String): LiveChatPage? =
            withContext(Dispatchers.IO) {
                val response =
                    try {
                        YouTube.liveChat(continuation).getOrNull()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Live chat poll failed: ${e.message}")
                        null
                    } ?: return@withContext null

                val cont = response.continuationContents?.liveChatContinuation ?: return@withContext null
                val continuationObj = cont.continuations.firstOrNull()
                val next = continuationObj?.token()
                val timeout = pollDelayMs(continuationObj?.timeoutMs())
                val messages = cont.actions.mapNotNull { it.addChatItemAction?.item?.toMessage() }
                LiveChatPage(messages = messages, nextContinuation = next, timeoutMs = timeout)
            }

        /** A message of a finished stream's chat, and when in the video it was sent. */
        data class ReplayMessage(
            val offsetMs: Long,
            val message: LiveChatMessage,
        )

        data class ReplayPage(
            val messages: List<ReplayMessage>,
            val nextContinuation: String?,
            val seekContinuation: String?,
        )

        /** The replay's messages from [offsetMs] on, through `get_live_chat_replay`: the live endpoint refuses a replay token. */
        suspend fun replayPage(
            continuation: String,
            offsetMs: Long,
        ): ReplayPage? =
            withContext(Dispatchers.IO) {
                val response =
                    try {
                        YouTube.liveChat(continuation, offsetMs, replay = true).getOrNull()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Chat replay fetch failed: ${e.message}")
                        null
                    }
                response?.continuationContents?.liveChatContinuation?.let(::replayPageOf)
            }

        internal fun replayPageOf(continuation: GetLiveChatResponse.LiveChatContinuation): ReplayPage =
            ReplayPage(
                messages =
                    continuation.actions.flatMap { action ->
                        val replay = action.replayChatItemAction ?: return@flatMap emptyList()
                        val offsetMs = replay.videoOffsetTimeMsec?.toLongOrNull() ?: return@flatMap emptyList()
                        replay.actions.mapNotNull { inner ->
                            inner.addChatItemAction
                                ?.item
                                ?.toMessage()
                                ?.let { ReplayMessage(offsetMs, it) }
                        }
                    },
                nextContinuation =
                    continuation.continuations.firstNotNullOfOrNull { it.liveChatReplayContinuationData?.continuation },
                seekContinuation =
                    continuation.continuations.firstNotNullOfOrNull { it.playerSeekContinuationData?.continuation },
            )

        private fun GetLiveChatResponse.Action.Item.toMessage(): LiveChatMessage? {
            liveChatTextMessageRenderer?.let { return it.toMessage(LiveChatMessageType.TEXT) }
            liveChatPaidMessageRenderer?.let { return it.toMessage(LiveChatMessageType.SUPER_CHAT) }
            liveChatMembershipItemRenderer?.let { return it.toMessage(LiveChatMessageType.MEMBERSHIP) }
            return null
        }

        private fun GetLiveChatResponse.ChatMessageRenderer.toMessage(type: LiveChatMessageType): LiveChatMessage? {
            val msgId = id ?: return null
            val bodyMessage =
                when (type) {
                    LiveChatMessageType.MEMBERSHIP -> {
                        message?.takeIf { it.plainText().isNotBlank() } ?: headerSubtext
                    }

                    else -> {
                        message
                    }
                }
            val text = bodyMessage?.plainText().orEmpty()
            val segments = bodyMessage?.toSegments().orEmpty()

            var isOwner = false
            var isModerator = false
            var isVerified = false
            var isMember = false
            var memberBadgeUrl: String? = null
            authorBadges.forEach { badge ->
                val renderer = badge.liveChatAuthorBadgeRenderer ?: return@forEach
                when (renderer.icon?.iconType?.uppercase()) {
                    "OWNER" -> isOwner = true
                    "MODERATOR" -> isModerator = true
                    "VERIFIED" -> isVerified = true
                }
                renderer.customThumbnail?.bestUrl()?.let {
                    isMember = true
                    memberBadgeUrl = it
                }
            }

            return LiveChatMessage(
                id = msgId,
                author = authorName?.value() ?: "",
                authorPhotoUrl = authorPhoto?.bestUrl(),
                message = text,
                segments = segments,
                timestamp = timestampText?.value(),
                type = type,
                isOwner = isOwner,
                isModerator = isModerator,
                isVerified = isVerified,
                isMember = isMember,
                memberBadgeUrl = memberBadgeUrl,
                superChatAmount = purchaseAmountText?.value(),
                superChatArgb = bodyBackgroundColor?.toInt(),
                superChatHeaderArgb = headerBackgroundColor?.toInt(),
            )
        }

        private fun GetLiveChatResponse.Message.toSegments(): List<LiveChatSegment> =
            runs.mapNotNull { run ->
                val emoji = run.emoji
                when {
                    run.text != null -> {
                        LiveChatSegment(text = run.text)
                    }

                    emoji != null -> {
                        val isCustom = emoji.isCustomEmoji == true
                        val unicode = emoji.emojiId?.takeIf { !isCustom && it.isNotBlank() }
                        if (unicode != null) {
                            LiveChatSegment(text = unicode)
                        } else {
                            LiveChatSegment(
                                text = emoji.shortcuts.firstOrNull() ?: emoji.emojiId ?: "",
                                emojiImageUrl = emoji.image?.bestUrl(),
                            )
                        }
                    }

                    else -> {
                        null
                    }
                }
            }

        companion object {
            private const val TAG = "LiveChatRepository"
            private const val DEFAULT_TIMEOUT_MS = 1000L
            private const val MIN_TIMEOUT_MS = 1000L
            private const val MAX_TIMEOUT_MS = 10_000L

            /** How long to wait before the next poll: the server's own `timeoutMs`, longer on a quiet chat. */
            internal fun pollDelayMs(serverTimeoutMs: Long?): Long =
                (serverTimeoutMs ?: DEFAULT_TIMEOUT_MS).coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        }
    }
