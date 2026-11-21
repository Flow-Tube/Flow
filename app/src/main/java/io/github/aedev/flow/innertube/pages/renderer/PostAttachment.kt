package io.github.aedev.flow.innertube.pages.renderer

import io.github.aedev.flow.data.model.Playlist
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.innertube.pages.arrayOrNull
import io.github.aedev.flow.innertube.pages.objectOrNull
import io.github.aedev.flow.innertube.pages.youtubeText
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull

sealed interface PostAttachment {
    /** One case for one and many: a single-image post is a one-page gallery, not a separate layout. */
    data class Images(
        val urls: List<String>,
    ) : PostAttachment

    data class Poll(
        val choices: List<PollChoice>,
        val totalVotesText: String?,
    ) : PostAttachment

    data class SharedVideo(
        val video: Video,
    ) : PostAttachment

    data class SharedPlaylist(
        val playlist: Playlist,
    ) : PostAttachment
}

data class PollChoice(
    val text: String,
    val voteRatio: Float? = null,
    val voteCountText: String? = null,
)

/**
 * The multi-image case is tested first on purpose. `postMultiImageRenderer` nests one
 * `backstageImageRenderer` per photo, so a search that stops at the first image it finds returns
 * picture one of N and silently drops the rest — which is what the app did.
 */
internal fun JsonElement?.toPostAttachment(owner: FeedItemOwner): PostAttachment? {
    val node = objectOrNull() ?: return null
    node.multiImage()?.let { return it }
    node.singleImage()?.let { return it }
    node.poll()?.let { return it }
    node.sharedVideo(owner)?.let { return it }
    node.sharedPlaylist(owner)?.let { return it }
    return null
}

private fun JsonObject.multiImage(): PostAttachment.Images? {
    val images =
        this["postMultiImageRenderer"]
            .objectOrNull()
            ?.get("images")
            .arrayOrNull()
            ?: return null
    val urls =
        images.mapNotNull { entry ->
            entry
                .objectOrNull()
                ?.get("backstageImageRenderer")
                .objectOrNull()
                ?.get("image")
                .largestImageUrl()
        }
    return urls.takeIf { it.isNotEmpty() }?.let(PostAttachment::Images)
}

private fun JsonObject.singleImage(): PostAttachment.Images? =
    this["backstageImageRenderer"]
        .objectOrNull()
        ?.get("image")
        .largestImageUrl()
        ?.let { PostAttachment.Images(listOf(it)) }

private fun JsonObject.poll(): PostAttachment.Poll? {
    val renderer = this["pollRenderer"].objectOrNull() ?: return null
    val choices =
        renderer["choices"]
            .arrayOrNull()
            .orEmpty()
            .mapNotNull { entry ->
                val choice = entry.objectOrNull() ?: return@mapNotNull null
                val text = choice["text"].youtubeText()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                PollChoice(
                    text = text,
                    voteRatio = (choice["voteRatioIfSelected"] as? JsonPrimitive)?.floatOrNull,
                    voteCountText =
                        choice["numVotes"].youtubeText()
                            ?: choice["votePercentageIfSelected"].youtubeText(),
                )
            }
    return choices.takeIf { it.isNotEmpty() }?.let {
        PostAttachment.Poll(choices = it, totalVotesText = renderer["totalVotes"].youtubeText())
    }
}

private fun JsonObject.sharedVideo(owner: FeedItemOwner): PostAttachment.SharedVideo? {
    val renderer = this["videoRenderer"].objectOrNull() ?: return null
    val item = FEED_ITEM_PARSERS.getValue("videoRenderer").parse(renderer, owner)
    return (item as? FeedItem.VideoItem)?.let { PostAttachment.SharedVideo(it.video) }
}

private val SharedPlaylistRenderers = listOf("playlistRenderer", "lockupViewModel")

private fun JsonObject.sharedPlaylist(owner: FeedItemOwner): PostAttachment.SharedPlaylist? =
    SharedPlaylistRenderers
        .firstNotNullOfOrNull { key ->
            val renderer = this[key].objectOrNull() ?: return@firstNotNullOfOrNull null
            (FEED_ITEM_PARSERS.getValue(key).parse(renderer, owner) as? FeedItem.PlaylistItem)?.playlist
        }?.let(PostAttachment::SharedPlaylist)
