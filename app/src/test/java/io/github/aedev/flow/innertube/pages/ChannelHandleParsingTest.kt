package io.github.aedev.flow.innertube.pages

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.repository.WatchMetadataVideoMapper
import io.github.aedev.flow.data.repository.decodeWatchMetadata
import io.github.aedev.flow.innertube.pages.channel.ChannelTabKind
import io.github.aedev.flow.innertube.pages.channel.toChannelTabContent
import io.github.aedev.flow.innertube.pages.renderer.FeedItemOwner
import io.github.aedev.flow.innertube.pages.renderer.videoOrNull
import io.github.aedev.flow.innertube.pages.search.resultVideos
import io.github.aedev.flow.innertube.pages.search.toSearchResultsPage
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * The AI channel list holds handles, so every surface that can read a channel's handle must keep
 * it. YouTube puts it in different places per renderer; these are the real captures.
 */
class ChannelHandleParsingTest {
    private fun resource(name: String) =
        Json.parseToJsonElement(requireNotNull(javaClass.classLoader?.getResourceAsStream(name)).bufferedReader().use { it.readText() })

    @Test
    fun `search video rows keep the channel handle from their byline`() {
        val videos = SearchFixture(SearchFixture.ALL_SAM_SULEK).toSearchResultsPage().resultVideos()

        assertThat(videos).isNotEmpty()
        videos.forEach { video -> assertThat(video.channelHandle).startsWith("/@") }
    }

    @Test
    fun `related lockups keep the handle that only their avatar carries`() {
        val related = WatchMetadataVideoMapper.relatedVideos(decodeWatchMetadata(resource("watch_next_related_lockups.json"))!!)

        assertThat(related.map { it.channelHandle }).containsExactly("/@ElHusamoX", "/@LinusTechTips")
    }

    @Test
    fun `a channel home tab lockup takes the avatar handle only when it is the owner's`() {
        val ltt = "UCXuqSBlHAE6Xw-yeJA0Tunw"
        val content = resource("channel/landing.json").toChannelTabContent(ChannelTabKind.Home, FeedItemOwner(id = ltt, name = "LTT"))
        val videos = (content.items + content.sections.flatMap { it.items }).mapNotNull { it.videoOrNull() }

        val withHandle = videos.filter { it.channelHandle != null }
        assertThat(withHandle).isNotEmpty()
        withHandle.filter { it.channelId == ltt }.forEach { assertThat(it.channelHandle).isEqualTo("/@LinusTechTips") }
    }
}
