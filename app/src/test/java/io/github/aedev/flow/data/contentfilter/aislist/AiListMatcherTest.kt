package io.github.aedev.flow.data.contentfilter.aislist

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.Video
import org.junit.Test

class AiListMatcherTest {
    private val listedId = "UCbbbbbbbbbbbbbbbbbbbbbb"
    private val otherId = "UCoooooooooooooooooooooo"

    private fun matcher(
        block: Boolean = true,
        warn: Boolean = true,
        exemptIds: Set<String> = emptySet(),
        exemptHandles: Set<String> = emptySet(),
        index: ChannelHandleIndex? = ChannelHandleIndex(directory = null, scope = null),
    ) = AiListMatcher(
        blockEnabled = block,
        warnEnabled = warn,
        block = AiListSets(handles = setOf("drew", "slopmaker"), ids = setOf(listedId)),
        warn = AiListSets(handles = setOf("maybeslop"), ids = emptySet()),
        exemptChannelIds = exemptIds,
        exemptHandles = exemptHandles,
        index = index,
    )

    private fun video(
        channelId: String = otherId,
        handle: String? = null,
    ) = Video(
        id = "v",
        title = "t",
        channelName = "c",
        channelId = channelId,
        thumbnailUrl = "",
        duration = 0,
        viewCount = 0,
        uploadDate = "",
        channelHandle = handle,
    )

    @Test
    fun `a listed handle matches only as a whole handle`() {
        val matcher = matcher()

        assertThat(matcher.verdict(video(handle = "/@Drew"))).isEqualTo(AiVerdict.BLOCK)
        assertThat(matcher.verdict(video(handle = "/@drewbinsky"))).isEqualTo(AiVerdict.NONE)
    }

    @Test
    fun `a listed channel id matches without a handle`() {
        assertThat(matcher().verdict(video(channelId = listedId))).isEqualTo(AiVerdict.BLOCK)
    }

    @Test
    fun `block and warn lists stay apart`() {
        val matcher = matcher()

        assertThat(matcher.verdict(video(handle = "@SlopMaker"))).isEqualTo(AiVerdict.BLOCK)
        assertThat(matcher.verdict(video(handle = "@MaybeSlop"))).isEqualTo(AiVerdict.WARN)
    }

    @Test
    fun `with hiding off a block-list channel is still labelled`() {
        assertThat(matcher(block = false).verdict(video(handle = "@SlopMaker"))).isEqualTo(AiVerdict.WARN)
        assertThat(matcher(warn = false).verdict(video(handle = "@MaybeSlop"))).isEqualTo(AiVerdict.NONE)
    }

    @Test
    fun `a subscribed or allowed channel is never flagged`() {
        assertThat(matcher(exemptIds = setOf(otherId)).verdict(video(handle = "@SlopMaker"))).isEqualTo(AiVerdict.NONE)
        assertThat(matcher(exemptHandles = setOf("slopmaker")).verdict(video(handle = "@SlopMaker"))).isEqualTo(AiVerdict.NONE)
    }

    @Test
    fun `a channel id seen once with its handle matches later without it`() {
        val matcher = matcher()
        val learnedId = "UCllllllllllllllllllllll"

        assertThat(matcher.verdict(video(channelId = learnedId))).isEqualTo(AiVerdict.NONE)
        matcher.verdict(video(channelId = learnedId, handle = "/@SlopMaker"))

        assertThat(matcher.verdict(video(channelId = learnedId))).isEqualTo(AiVerdict.BLOCK)
    }

    @Test
    fun `a channel page teaches the index too`() {
        val matcher = matcher()
        val learnedId = "UCcccccccccccccccccccccc"

        matcher.learn(learnedId, "@MaybeSlop")

        assertThat(matcher.verdict(learnedId, null)).isEqualTo(AiVerdict.WARN)
    }

    @Test
    fun `the off matcher answers nothing`() {
        assertThat(AiListMatcher.NONE.isActive).isFalse()
        assertThat(AiListMatcher.NONE.verdict(video(channelId = listedId))).isEqualTo(AiVerdict.NONE)
    }

    @Test
    fun `the index forgets the least recently used channel first`() {
        val index = ChannelHandleIndex(directory = null, scope = null, capacity = 2)
        index.learn("a", "one")
        index.learn("b", "two")
        index.handleFor("a")
        index.learn("c", "three")

        assertThat(index.handleFor("a")).isEqualTo("one")
        assertThat(index.handleFor("b")).isNull()
        assertThat(index.handleFor("c")).isEqualTo("three")
    }
}
