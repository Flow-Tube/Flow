package io.github.aedev.flow.innertube.pages.channel

import io.github.aedev.flow.innertube.pages.arrayOrNull
import io.github.aedev.flow.innertube.pages.objectOrNull
import io.github.aedev.flow.innertube.pages.renderer.findRenderers
import io.github.aedev.flow.innertube.pages.renderer.forEachObject
import io.github.aedev.flow.innertube.pages.renderer.largestImageUrl
import io.github.aedev.flow.innertube.pages.stringOrNull
import io.github.aedev.flow.innertube.pages.unwrapRedirectUrl
import io.github.aedev.flow.innertube.pages.youtubeText
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

data class ChannelAbout(
    val description: String? = null,
    val countryText: String? = null,
    val joinedDateText: String? = null,
    val viewCountText: String? = null,
    val videoCountText: String? = null,
    val subscriberCountText: String? = null,
    val canonicalUrl: String? = null,
    val links: List<ChannelLink> = emptyList(),
)

/**
 * The token for the About panel, which the landing response does **not** contain. The header's
 * "and N more links" suffix carries it on channels with several links; every channel carries it
 * behind the description preview's "...more", which is the only one a one-link channel has.
 */
internal fun JsonElement.channelAboutContinuation(): String? {
    var suffix: String? = null
    var description: String? = null
    forEachObject { node ->
        if (suffix == null) node["attributionViewModel"].objectOrNull()?.get("suffix")?.let { suffix = it.firstContinuationToken() }
        if (description == null) {
            node["pageHeaderViewModel"].objectOrNull()?.get("description")?.let { description = it.firstContinuationToken() }
        }
    }
    return suffix ?: description
}

private fun JsonElement.firstContinuationToken(): String? {
    var token: String? = null
    forEachObject { node ->
        if (token != null) return@forEachObject
        node["continuationCommand"]
            .objectOrNull()
            ?.get("token")
            .stringOrNull()
            ?.takeIf(String::isNotBlank)
            ?.let { token = it }
    }
    return token
}

internal fun JsonElement.toChannelAbout(): ChannelAbout? {
    val model = findRenderers("aboutChannelViewModel")["aboutChannelViewModel"] ?: return null
    return ChannelAbout(
        description = model["description"].youtubeText()?.trim()?.takeIf(String::isNotEmpty),
        countryText = model["country"].youtubeText(),
        joinedDateText = model["joinedDateText"].youtubeText(),
        viewCountText = model["viewCountText"].youtubeText(),
        videoCountText = model["videoCountText"].youtubeText(),
        subscriberCountText = model["subscriberCountText"].youtubeText(),
        canonicalUrl = model["canonicalChannelUrl"].stringOrNull(),
        links = model.aboutLinks(),
    )
}

private fun JsonObject.aboutLinks(): List<ChannelLink> =
    this["links"]
        .arrayOrNull()
        .orEmpty()
        .mapNotNull { entry ->
            val link = entry.objectOrNull()?.get("channelExternalLinkViewModel").objectOrNull() ?: return@mapNotNull null
            val title = link["title"].youtubeText()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val display = link["link"].youtubeText()?.takeIf(String::isNotBlank)
            val target = link["link"].linkTarget() ?: display ?: return@mapNotNull null
            ChannelLink(
                title = title,
                displayText = display ?: title,
                url = target,
                iconUrl = link["favicon"].largestImageUrl(),
            )
        }

/**
 * Every external link is wrapped in `youtube.com/redirect?...&q=<target>`, so the visible text is the
 * only human-readable part and the wrapper has to come off before the link can be opened.
 */
private fun JsonElement?.linkTarget(): String? {
    var url: String? = null
    forEachObject { node ->
        if (url != null) return@forEachObject
        node["urlEndpoint"]
            .objectOrNull()
            ?.get("url")
            .stringOrNull()
            ?.takeIf(String::isNotBlank)
            ?.let { url = unwrapRedirectUrl(it) }
    }
    return url
}
