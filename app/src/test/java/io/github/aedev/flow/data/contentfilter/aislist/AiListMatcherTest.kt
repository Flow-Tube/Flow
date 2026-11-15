package io.github.aedev.flow.data.contentfilter.aislist

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.music.model.ArtistDetails
import io.github.aedev.flow.data.music.model.MusicArtist
import io.github.aedev.flow.data.music.model.MusicPlaylist
import io.github.aedev.flow.data.music.model.MusicTrack
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

class AiListMatcherMusicTest {
    private val aiArtist = "UCaiaiaiaiaiaiaiaiaiaiai"
    private val realArtist = "UCrealrealrealrealrealre"
    private val listedUploader = "UCbbbbbbbbbbbbbbbbbbbbbb"
    private val warnUploader = "UCwwwwwwwwwwwwwwwwwwwwww"

    private fun matcher(
        block: Boolean = false,
        warn: Boolean = false,
        music: Boolean = true,
        exemptIds: Set<String> = emptySet(),
    ) = AiListMatcher(
        blockEnabled = block,
        warnEnabled = warn,
        block = AiListSets(handles = emptySet(), ids = setOf(listedUploader)),
        warn = AiListSets(handles = emptySet(), ids = setOf(warnUploader)),
        exemptChannelIds = exemptIds,
        exemptHandles = emptySet(),
        index = null,
        musicEnabled = music,
        music = AiListSets(handles = emptySet(), ids = setOf(aiArtist)),
    )

    private fun track(
        vararg artistIds: String?,
        channelId: String = "",
    ) = MusicTrack(
        videoId = "v",
        title = "t",
        artist = "a",
        thumbnailUrl = "",
        duration = 0,
        channelId = channelId,
        artists = artistIds.map { MusicArtist(name = "n", id = it) },
    )

    @Test
    fun `a song by a listed artist is hidden, including a collaboration`() {
        val matcher = matcher()

        assertThat(matcher.hides(track(aiArtist))).isTrue()
        assertThat(matcher.hides(track(realArtist, aiArtist))).isTrue()
        assertThat(matcher.hides(track(realArtist, null))).isFalse()
    }

    @Test
    fun `the music list does nothing while its switch is off, and never touches video verdicts`() {
        assertThat(matcher(music = false).hides(track(aiArtist))).isFalse()
        assertThat(matcher(music = false).isMusicActive).isFalse()
        assertThat(matcher(block = true).verdict(aiArtist, null)).isEqualTo(AiVerdict.NONE)
    }

    @Test
    fun `an AiSList channel id hides its uploads in Music only while hiding AI channels is on`() {
        assertThat(matcher(music = false, block = true).hides(track(channelId = listedUploader))).isTrue()
        assertThat(matcher(music = true, block = false).hides(track(channelId = listedUploader))).isFalse()
        assertThat(matcher(music = false, block = false, warn = true).verdict(track(channelId = listedUploader)))
            .isEqualTo(AiVerdict.WARN)
        assertThat(matcher(music = false, warn = true).verdict(track(channelId = warnUploader))).isEqualTo(AiVerdict.WARN)
    }

    @Test
    fun `an allowed or subscribed artist is exempt, but a listed co-artist still counts`() {
        val matcher = matcher(exemptIds = setOf(aiArtist))

        assertThat(matcher.hides(track(aiArtist))).isFalse()
        assertThat(matcher.artistVerdict(aiArtist)).isEqualTo(AiVerdict.NONE)
        assertThat(matcher(block = true, exemptIds = setOf(aiArtist)).hides(track(aiArtist, channelId = listedUploader))).isTrue()
    }

    @Test
    fun `albums and artist cards match by their structured id`() {
        val matcher = matcher()
        val album =
            MusicPlaylist(
                id = "MPRE",
                title = "t",
                thumbnailUrl = "",
                author = "2026",
                authorId = aiArtist,
            )
        val artist =
            ArtistDetails(
                name = "n",
                channelId = aiArtist,
                thumbnailUrl = "",
                subscriberCount = 0,
            )

        assertThat(matcher.hides(album)).isTrue()
        assertThat(matcher.hides(album.copy(authorId = null))).isFalse()
        assertThat(matcher.hides(artist)).isTrue()
        assertThat(matcher.artistVerdict(aiArtist)).isEqualTo(AiVerdict.BLOCK)
        assertThat(AiListMatcher.NONE.hides(artist)).isFalse()
    }
}
