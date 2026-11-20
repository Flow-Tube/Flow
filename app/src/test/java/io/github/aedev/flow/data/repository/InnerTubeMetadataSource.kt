package io.github.aedev.flow.data.repository

import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.models.response.PlayerResponse
import io.github.aedev.flow.innertube.pages.channel.ChannelHeader
import io.github.aedev.flow.innertube.pages.channel.ChannelPage
import io.mockk.coEvery
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.CopyOnWriteArrayList

/** An image as YouTube lists it. */
internal data class SourceImage(
    val url: String,
    val width: Int,
    val height: Int,
)

/** What YouTube says about one video: the player response's details and microformat, and the watch page's owner. */
internal data class SourceWatchPage(
    val title: String?,
    val channelName: String?,
    val channelId: String,
    val thumbnails: List<SourceImage>,
    val avatars: List<SourceImage>,
    val durationSeconds: Long,
    val viewCount: Long,
    /** The microformat's ISO upload date. */
    val uploadDate: String?,
    /** The watch page's own date line, used when the microformat has none. */
    val dateText: String? = null,
    val category: String? = null,
)

/** What YouTube says about one channel. */
internal data class SourceChannel(
    val id: String,
    val name: String,
    val avatarUrl: String,
    val tags: List<String>,
    val description: String?,
    val handle: String? = null,
)

/**
 * Serves video and channel metadata to [YouTubeRepository] as InnerTube answers it: `/player`
 * (details and microformat), `/next` (the owner) and the channel browse.
 */
internal class InnerTubeMetadataSource {
    private val pages = mutableMapOf<String, SourceWatchPage>()
    private val channels = mutableMapOf<String, SourceChannel>()
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    /** Every `/player` request for a video's details, by video id, in order. */
    val playerRequests = CopyOnWriteArrayList<String>()

    /** Every `/next` request, by video id, in order. */
    val watchRequests = CopyOnWriteArrayList<String>()

    /** Every channel browse, by channel id, in order. */
    val channelRequests = CopyOnWriteArrayList<String>()

    /** Every link resolved to a channel id, in order. */
    val resolvedLinks = CopyOnWriteArrayList<String>()

    fun install() {
        mockkObject(YouTube)
        coEvery { YouTube.videoSummary(any()) } answers {
            val id = firstArg<String>()
            playerRequests += id
            pages[id]?.let { Result.success(playerResponse(id, it)) } ?: Result.failure(java.io.IOException("missing $id"))
        }
        coEvery { YouTube.watchNextJson(any()) } answers {
            val id = firstArg<String>()
            watchRequests += id
            pages[id]?.let { Result.success(watchPage(it)) } ?: Result.failure(java.io.IOException("missing $id"))
        }
        coEvery { YouTube.channelLanding(any()) } answers {
            val id = firstArg<String>()
            channelRequests += id
            channels[id]?.let { Result.success(ChannelPage(header(it), emptyList(), null)) }
                ?: Result.failure(java.io.IOException("missing $id"))
        }
        coEvery { YouTube.resolveChannelId(any()) } answers {
            val link = firstArg<String>()
            resolvedLinks += link
            channels.values
                .firstOrNull { link.endsWith("/@${it.handle ?: it.name}") }
                ?.let { Result.success(it.id) }
                ?: Result.failure(IllegalStateException("No channel behind $link"))
        }
    }

    fun uninstall() {
        unmockkObject(YouTube)
    }

    fun serveWatchPage(
        videoId: String,
        page: SourceWatchPage,
    ) {
        pages[videoId] = page
    }

    fun serveChannel(channel: SourceChannel) {
        channels[channel.id] = channel
    }

    private fun header(channel: SourceChannel) =
        ChannelHeader(
            id = channel.id,
            title = channel.name,
            handle = channel.handle,
            avatarUrl = channel.avatarUrl,
            description = channel.description,
            keywords = channel.tags,
        )

    private fun images(images: List<SourceImage>): JsonArray =
        buildJsonArray {
            images.forEach { image ->
                addJsonObject {
                    put("url", image.url)
                    put("width", image.width)
                    put("height", image.height)
                }
            }
        }

    private fun playerResponse(
        videoId: String,
        page: SourceWatchPage,
    ): PlayerResponse {
        val raw =
            buildJsonObject {
                putJsonObject("responseContext") {}
                putJsonObject("playabilityStatus") {
                    put("status", "UNPLAYABLE")
                    put("reason", "The page needs to be reloaded.")
                }
                putJsonObject("videoDetails") {
                    put("videoId", videoId)
                    page.title?.let { put("title", it) }
                    page.channelName?.let { put("author", it) }
                    put("channelId", page.channelId)
                    put("lengthSeconds", page.durationSeconds.toString())
                    put("viewCount", page.viewCount.toString())
                    putJsonObject("thumbnail") { put("thumbnails", images(page.thumbnails)) }
                }
                putJsonObject("microformat") {
                    putJsonObject("playerMicroformatRenderer") {
                        page.uploadDate?.let {
                            put("uploadDate", it)
                            put("publishDate", it)
                        }
                        page.category?.let { put("category", it) }
                    }
                }
            }
        return json.decodeFromJsonElement(PlayerResponse.serializer(), raw)
    }

    private fun watchPage(page: SourceWatchPage): JsonObject =
        buildJsonObject {
            putJsonObject("contents") {
                putJsonObject("twoColumnWatchNextResults") {
                    putJsonObject("results") {
                        putJsonObject("results") {
                            putJsonArray("contents") {
                                addJsonObject {
                                    putJsonObject("videoPrimaryInfoRenderer") {
                                        page.dateText?.let { putJsonObject("dateText") { put("simpleText", it) } }
                                    }
                                }
                                addJsonObject {
                                    putJsonObject("videoSecondaryInfoRenderer") {
                                        putJsonObject("owner") {
                                            putJsonObject("videoOwnerRenderer") {
                                                putJsonObject("thumbnail") { put("thumbnails", images(page.avatars)) }
                                                page.channelName?.let { name ->
                                                    putJsonObject(
                                                        "title",
                                                    ) { putJsonArray("runs") { addJsonObject { put("text", JsonPrimitive(name)) } } }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
}
