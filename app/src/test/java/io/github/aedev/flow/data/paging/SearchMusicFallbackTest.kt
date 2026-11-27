package io.github.aedev.flow.data.paging

import androidx.paging.PagingSource
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.ContentType
import io.github.aedev.flow.data.local.SearchFilter
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.recommendation.FeedExclusions
import io.github.aedev.flow.innertube.models.Artist
import io.github.aedev.flow.innertube.models.SongItem
import io.github.aedev.flow.innertube.pages.SearchFixture
import io.github.aedev.flow.innertube.pages.search.SearchResultsPage
import io.github.aedev.flow.innertube.pages.search.toSearchResultsPage
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SearchMusicFallbackTest {
    private val gated = SearchResultsPage(continuation = "next", ageGated = true)

    private fun video(
        id: String,
        channelId: String = "UCartist",
    ) = Video(
        id = id,
        title = id,
        channelName = "Artist",
        channelId = channelId,
        thumbnailUrl = "",
        duration = 200,
        viewCount = 0,
        uploadDate = "",
    )

    private class Recorder(
        private val videos: List<Video>,
    ) : MusicVideoSearch {
        val queries = mutableListOf<String>()

        override suspend fun invoke(query: String): List<Video> {
            queries += query
            return videos
        }
    }

    private fun source(
        page: SearchResultsPage,
        music: MusicVideoSearch,
        filter: SearchFilter = SearchFilter.DEFAULT,
        onMusicFallback: () -> Unit = {},
        blocked: Set<String> = emptySet(),
    ) = SearchPagingSource(
        query = "fuck",
        filter = filter,
        loadPage = SearchPageLoader { _, _, _ -> page },
        musicVideoSearch = music,
        onMusicFallback = onMusicFallback,
    ) { FeedExclusions(blockedChannelIds = blocked) }

    private suspend fun SearchPagingSource.firstPage() =
        load(PagingSource.LoadParams.Refresh(null, 20, false)) as PagingSource.LoadResult.Page

    @Test
    fun `an age checked first page is answered by one music search with no further pages`() =
        runTest {
            val music = Recorder(listOf(video("a"), video("b")))
            var noted = 0
            val page = source(gated, music, onMusicFallback = { noted++ }).firstPage()

            assertThat(music.queries).containsExactly("fuck")
            assertThat(noted).isEqualTo(1)
            assertThat(page.data.map { (it as SearchResultItem.VideoResult).video.id }).containsExactly("a", "b").inOrder()
            assertThat(page.nextKey).isNull()
        }

    @Test
    fun `music results still drop blocked channels`() =
        runTest {
            val music = Recorder(listOf(video("a"), video("b", channelId = "UCblocked")))
            val page = source(gated, music, blocked = setOf("UCblocked")).firstPage()

            assertThat(page.data.map { (it as SearchResultItem.VideoResult).video.id }).containsExactly("a")
        }

    @Test
    fun `an ordinary page never asks YouTube Music`() =
        runTest {
            val music = Recorder(listOf(video("a")))
            source(SearchFixture(SearchFixture.ALL_SAM_SULEK).toSearchResultsPage(), music).firstPage()

            assertThat(music.queries).isEmpty()
        }

    @Test
    fun `only the all and videos filters fall back`() {
        val fallsBack = ContentType.entries.filter { gated.needsMusicFallback(it) }

        assertThat(fallsBack).containsExactly(ContentType.ALL, ContentType.VIDEOS)
    }

    @Test
    fun `a music video row becomes a dateless search video`() {
        val video =
            SongItem(
                id = "abc123",
                title = "Song",
                artists = listOf(Artist("Singer", "UCsinger"), Artist("Guest", null)),
                duration = 223,
                thumbnail = "https://lh3.googleusercontent.com/x=w120-h120",
                viewCountText = "1.2M views",
            ).toSearchVideo("en")!!

        assertThat(video.channelName).isEqualTo("Singer, Guest")
        assertThat(video.channelId).isEqualTo("UCsinger")
        assertThat(video.viewCount).isEqualTo(1_200_000L)
        assertThat(video.duration).isEqualTo(223)
        assertThat(video.uploadDate).isEmpty()
        assertThat(video.timestamp).isEqualTo(0L)
        assertThat(video.thumbnailUrl).contains("abc123")
    }
}
