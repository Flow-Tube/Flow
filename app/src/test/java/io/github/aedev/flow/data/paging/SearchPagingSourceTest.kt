package io.github.aedev.flow.data.paging

import androidx.paging.PagingSource
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.ContentType
import io.github.aedev.flow.data.local.Duration
import io.github.aedev.flow.data.local.SearchFilter
import io.github.aedev.flow.data.local.SortType
import io.github.aedev.flow.data.local.UploadDate
import io.github.aedev.flow.data.local.WatchState
import io.github.aedev.flow.data.recommendation.FeedExclusions
import io.github.aedev.flow.innertube.pages.SearchFixture
import io.github.aedev.flow.innertube.pages.renderer.FeedShelfStyle
import io.github.aedev.flow.innertube.pages.renderer.videoOrNull
import io.github.aedev.flow.innertube.pages.search.SearchHeader
import io.github.aedev.flow.innertube.pages.search.SearchSection
import io.github.aedev.flow.innertube.pages.search.toSearchResultsPage
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import java.util.concurrent.Executors

class SearchPagingSourceTest {
    private class RecordingLoader(
        private val pages: List<String>,
    ) : SearchPageLoader {
        val calls = mutableListOf<Triple<String, String?, String?>>()
        var failNext = false

        override suspend fun invoke(
            query: String,
            params: String?,
            continuation: String?,
        ) = run {
            calls += Triple(query, params, continuation)
            if (failNext) {
                failNext = false
                throw IllegalStateException("boom")
            }
            SearchFixture(pages[(calls.size - 1).coerceAtMost(pages.lastIndex)]).toSearchResultsPage()
        }
    }

    private fun source(
        loader: SearchPageLoader,
        filter: SearchFilter = SearchFilter.DEFAULT,
        shortsEnabled: Boolean = true,
        onHeader: (SearchHeader) -> Unit = {},
        blocked: Set<String> = emptySet(),
        watched: WatchedVideoLookup = WatchedVideoLookup { emptySet() },
    ) = SearchPagingSource("sam sulek", filter, shortsEnabled, onHeader, loader, watched) { FeedExclusions(blockedChannelIds = blocked) }

    private suspend fun SearchPagingSource.loadPage(key: String? = null) =
        load(PagingSource.LoadParams.Refresh(key, 20, false)) as PagingSource.LoadResult.Page

    @Test
    fun `issues exactly one request per page`() =
        runTest {
            val loader = RecordingLoader(listOf(SearchFixture.ALL_SAM_SULEK))
            source(loader).loadPage()

            assertThat(loader.calls).hasSize(1)
        }

    @Test
    fun `a page is fetched and parsed on the load dispatcher`() =
        runTest {
            val executor = Executors.newSingleThreadExecutor { Thread(it, "search-load") }
            try {
                var loadThread: String? = null
                val loader =
                    SearchPageLoader { _, _, _ ->
                        loadThread = Thread.currentThread().name
                        SearchFixture(SearchFixture.ALL_SAM_SULEK).toSearchResultsPage()
                    }
                SearchPagingSource("sam sulek", loadPage = loader, loadContext = executor.asCoroutineDispatcher()).loadPage()

                assertThat(loadThread).isEqualTo("search-load")
            } finally {
                executor.shutdown()
            }
        }

    @Test
    fun `the first page carries the query and the params, a later page carries only the token`() =
        runTest {
            val loader = RecordingLoader(listOf(SearchFixture.ALL_SAM_SULEK, SearchFixture.ALL_CONTINUATION))
            val paging = source(loader, SearchFilter(contentType = ContentType.VIDEOS))

            val first = paging.loadPage()
            paging.loadPage(first.nextKey)

            assertThat(loader.calls[0].second).isEqualTo("EgIQAQ%3D%3D")
            assertThat(loader.calls[0].third).isNull()
            assertThat(loader.calls[1].third).isEqualTo(first.nextKey)
        }

    @Test
    fun `hands the next page token back as the paging key`() =
        runTest {
            val page = source(RecordingLoader(listOf(SearchFixture.ALL_SAM_SULEK))).loadPage()

            assertThat(page.prevKey).isNull()
            assertThat(page.nextKey).isNotNull()
        }

    @Test
    fun `reports the header from the first page only`() =
        runTest {
            val headers = mutableListOf<SearchHeader>()
            val loader = RecordingLoader(listOf(SearchFixture.ALL_SAM_SULEK, SearchFixture.ALL_CONTINUATION))
            val paging = source(loader, onHeader = { headers += it })

            val first = paging.loadPage()
            paging.loadPage(first.nextKey)

            assertThat(headers).hasSize(1)
            assertThat(headers.single().filterGroups).hasSize(5)
        }

    @Test
    fun `keeps every strip distinct across the page`() =
        runTest {
            val page = source(RecordingLoader(listOf(SearchFixture.ALL_SAM_SULEK))).loadPage()
            val shelves = page.data.filterIsInstance<SearchResultItem.ShelfResult>()

            assertThat(shelves.map { it.id }).containsNoDuplicates()
            assertThat(shelves.count { it.kind == SearchShelfKind.SHORTS }).isEqualTo(2)
        }

    @Test
    fun `folds the latest-from strip into the creator card it belongs to`() =
        runTest {
            val page = source(RecordingLoader(listOf(SearchFixture.ALL_SAM_SULEK))).loadPage()
            val creator = page.data.filterIsInstance<SearchResultItem.ChannelResult>().single()

            assertThat(creator.latestVideos).isNotEmpty()
            assertThat(creator.latestTitle).isNotNull()
            assertThat(
                page.data.filterIsInstance<SearchResultItem.ShelfResult>().none {
                    it.kind == SearchShelfKind.VIDEOS
                },
            ).isTrue()
        }

    @Test
    fun `de-duplicates a result that comes back on a later page`() =
        runTest {
            val loader = RecordingLoader(listOf(SearchFixture.ALL_SAM_SULEK, SearchFixture.ALL_SAM_SULEK))
            val paging = source(loader)

            val first = paging.loadPage()
            val second = paging.loadPage(first.nextKey)

            assertThat(first.data).isNotEmpty()
            assertThat(second.data).isEmpty()
        }

    @Test
    fun `hides shorts and shorts strips when the user turned shorts off`() =
        runTest {
            val page = source(RecordingLoader(listOf(SearchFixture.ALL_SAM_SULEK)), shortsEnabled = false).loadPage()

            assertThat(
                page.data.filterIsInstance<SearchResultItem.ShelfResult>().none {
                    it.kind == SearchShelfKind.SHORTS
                },
            ).isTrue()
        }

    @Test
    fun `passes the shorts type as a params token instead of a second request`() =
        runTest {
            val loader = RecordingLoader(listOf(SearchFixture.TYPE_SHORTS))
            source(loader, SearchFilter(contentType = ContentType.SHORTS)).loadPage()

            assertThat(loader.calls).hasSize(1)
            assertThat(loader.calls.single().second).isEqualTo("EgIQCQ%3D%3D")
        }

    @Test
    fun `the shorts filter lays its shorts strips out as single shorts`() =
        runTest {
            val page = source(RecordingLoader(listOf(SearchFixture.TYPE_SHORTS)), SearchFilter(contentType = ContentType.SHORTS)).loadPage()

            assertThat(page.data).isNotEmpty()
            assertThat(page.data.all { it is SearchResultItem.VideoResult && it.video.isShort }).isTrue()
            assertThat(page.data.videoIds()).containsAtLeastElementsIn(SearchFixture(SearchFixture.TYPE_SHORTS).stripShortIds())
        }

    @Test
    fun `a later shorts filter page keeps its shorts although its strips reuse the first page's ids`() =
        runTest {
            val first = SearchFixture(SearchFixture.TYPE_SHORTS)
            val ids = first.stripShortIds()
            val second = Json.parseToJsonElement(ids.fold(first.toString()) { text, id -> text.replace(id, id.reversed()) }).jsonObject
            val pages = mutableListOf(first, second)
            val paging =
                SearchPagingSource(
                    query = "sam sulek",
                    filter = SearchFilter(contentType = ContentType.SHORTS),
                    loadPage = SearchPageLoader { _, _, _ -> pages.removeAt(0).toSearchResultsPage() },
                )

            val one = paging.loadPage()
            val two = paging.loadPage(one.nextKey)

            assertThat(two.data.videoIds()).containsAtLeastElementsIn(ids.map { it.reversed() })
        }

    @Test
    fun `builds one token for a combination of filters`() =
        runTest {
            val loader = RecordingLoader(listOf(SearchFixture.COMBINED_FILTERS))
            val filter =
                SearchFilter(
                    contentType = ContentType.VIDEOS,
                    duration = Duration.OVER_20_MINUTES,
                    uploadDate = UploadDate.THIS_YEAR,
                    sortType = SortType.VIEW_COUNT,
                )
            source(loader, filter).loadPage()

            assertThat(loader.calls.single().second).isEqualTo("CAMSBggFEAEYAg%3D%3D")
        }

    @Test
    fun `returns every item the response carried, filtering nothing on the client`() =
        runTest {
            val response = SearchFixture(SearchFixture.DURATION_UNDER_3).toSearchResultsPage()
            val page =
                source(
                    RecordingLoader(listOf(SearchFixture.DURATION_UNDER_3)),
                    SearchFilter(duration = Duration.UNDER_3_MINUTES),
                ).loadPage()

            assertThat(page.data).hasSize(response.toResultItems(shortsEnabled = true).size)
        }

    @Test
    fun `hides a blocked creator, their card and their videos`() =
        runTest {
            val loader = RecordingLoader(listOf(SearchFixture.ALL_SAM_SULEK))
            val visible = source(loader).loadPage()
            val creator = visible.data.filterIsInstance<SearchResultItem.ChannelResult>().single()

            val page =
                source(RecordingLoader(listOf(SearchFixture.ALL_SAM_SULEK)), blocked = setOf(creator.channel.id))
                    .loadPage()

            assertThat(page.data.filterIsInstance<SearchResultItem.ChannelResult>()).isEmpty()
            assertThat(
                page.data.filterIsInstance<SearchResultItem.VideoResult>().none {
                    it.video.channelId == creator.channel.id
                },
            ).isTrue()
            assertThat(
                page.data.filterIsInstance<SearchResultItem.ShelfResult>().none { shelf ->
                    shelf.videos.any { it.channelId == creator.channel.id }
                },
            ).isTrue()
        }

    @Test
    fun `blocking nobody leaves the page untouched`() =
        runTest {
            val open = source(RecordingLoader(listOf(SearchFixture.ALL_SAM_SULEK))).loadPage()
            val empty = source(RecordingLoader(listOf(SearchFixture.ALL_SAM_SULEK)), blocked = emptySet()).loadPage()

            assertThat(empty.data).hasSize(open.data.size)
        }

    @Test
    fun `a strip emptied by blocking is dropped rather than left as a heading`() {
        val shelf =
            SearchResultItem.ShelfResult(
                id = "s1",
                title = "Latest",
                kind = SearchShelfKind.VIDEOS,
                videos = listOf(video(channelId = "UCblocked")),
            )

        assertThat(listOf(shelf).withoutHidden(FeedExclusions(blockedChannelIds = setOf("UCblocked")))).isEmpty()
    }

    @Test
    fun `a video with no channel id survives blocking`() {
        val item = SearchResultItem.VideoResult(video(channelId = ""))

        assertThat(listOf(item).withoutHidden(FeedExclusions(blockedChannelIds = setOf("UCblocked")))).containsExactly(item)
    }

    @Test
    fun `a video marked not interested leaves the results but its channel stays`() {
        val hidden = SearchResultItem.VideoResult(video(channelId = "UCkept"))
        val channel =
            SearchResultItem.ChannelResult(
                io.github.aedev.flow.data.model
                    .Channel(id = "UCkept", name = "Kept", thumbnailUrl = "", subscriberCount = 0L),
            )

        val visible =
            listOf(hidden, channel).withoutHidden(
                FeedExclusions(suppressedVideoIds = setOf(hidden.video.id), suppressedChannelIds = setOf("UCkept")),
            )

        assertThat(visible).containsExactly(channel)
    }

    @Test
    fun `without a watch filter a page is still one request`() =
        runTest {
            val loader = RecordingLoader(listOf(SearchFixture.ALL_SAM_SULEK))
            source(loader, watched = { videos -> videos.mapTo(HashSet()) { it.id } }).loadPage()

            assertThat(loader.calls).hasSize(1)
        }

    @Test
    fun `Watched keeps only watched videos and drops channels and playlists`() =
        runTest {
            val all = source(RecordingLoader(listOf(SearchFixture.ALL_SAM_SULEK))).loadPage()
            val loose = all.data.filterIsInstance<SearchResultItem.VideoResult>().map { it.video.id }
            val watchedId = loose.first()

            val page =
                source(
                    RecordingLoader(listOf(SearchFixture.ALL_SAM_SULEK)),
                    SearchFilter(watchState = WatchState.WATCHED),
                    watched = { videos -> videos.map { it.id }.filter { it == watchedId }.toSet() },
                ).loadPage()

            assertThat(page.data.filterIsInstance<SearchResultItem.VideoResult>().map { it.video.id }).containsExactly(watchedId)
            assertThat(page.data.filterIsInstance<SearchResultItem.ChannelResult>()).isEmpty()
            assertThat(page.data.filterIsInstance<SearchResultItem.PlaylistResult>()).isEmpty()
        }

    @Test
    fun `Unwatched drops watched videos and keeps the rest`() =
        runTest {
            val all = source(RecordingLoader(listOf(SearchFixture.ALL_SAM_SULEK))).loadPage()
            val watchedId =
                all.data
                    .filterIsInstance<SearchResultItem.VideoResult>()
                    .first()
                    .video.id

            val page =
                source(
                    RecordingLoader(listOf(SearchFixture.ALL_SAM_SULEK)),
                    SearchFilter(watchState = WatchState.UNWATCHED),
                    watched = { setOf(watchedId) },
                ).loadPage()

            assertThat(page.data.filterIsInstance<SearchResultItem.VideoResult>().none { it.video.id == watchedId }).isTrue()
            assertThat(page.data).hasSize(all.data.size - 1)
        }

    @Test
    fun `a thinned page reads on, at most three requests per load`() =
        runTest {
            val loader = RecordingLoader(listOf(SearchFixture.ALL_SAM_SULEK, SearchFixture.ALL_CONTINUATION))
            val page = source(loader, SearchFilter(watchState = WatchState.WATCHED)).loadPage()

            assertThat(loader.calls).hasSize(3)
            assertThat(page.data).isEmpty()
            assertThat(loader.calls[1].third).isNotNull()
        }

    @Test
    fun `a watch filter that keeps finding nothing ends the search after two loads`() =
        runTest {
            val loader = RecordingLoader(listOf(SearchFixture.ALL_SAM_SULEK, SearchFixture.ALL_CONTINUATION))
            val paging = source(loader, SearchFilter(watchState = WatchState.WATCHED))

            val first = paging.loadPage()
            val second = paging.loadPage(first.nextKey)

            assertThat(first.nextKey).isNotNull()
            assertThat(second.nextKey).isNull()
            assertThat(loader.calls).hasSize(6)
        }

    @Test
    fun `a load that finds a match keeps the search going`() =
        runTest {
            val loader = RecordingLoader(listOf(SearchFixture.ALL_SAM_SULEK, SearchFixture.ALL_CONTINUATION))
            val paging = source(loader, SearchFilter(watchState = WatchState.UNWATCHED))

            val first = paging.loadPage()
            val second = paging.loadPage(first.nextKey)

            assertThat(first.data).isNotEmpty()
            assertThat(second.nextKey).isNotNull()
        }

    @Test
    fun `channel and playlist searches ignore the watch filter`() =
        runTest {
            val loader = RecordingLoader(listOf(SearchFixture.TYPE_CHANNELS))
            val page =
                source(loader, SearchFilter(contentType = ContentType.CHANNELS, watchState = WatchState.WATCHED)).loadPage()

            assertThat(loader.calls).hasSize(1)
            assertThat(page.data).isNotEmpty()
        }

    @Test
    fun `a strip keeps only the videos the watch filter allows`() {
        val shelf =
            SearchResultItem.ShelfResult(
                id = "s1",
                title = "Latest",
                kind = SearchShelfKind.VIDEOS,
                videos = listOf(video("UC1").copy(id = "a"), video("UC1").copy(id = "b")),
            )

        val watched = listOf(shelf).withWatchState(WatchState.WATCHED, setOf("a"))
        val unwatched = listOf(shelf).withWatchState(WatchState.UNWATCHED, setOf("a", "b"))

        assertThat((watched.single() as SearchResultItem.ShelfResult).videos.map { it.id }).containsExactly("a")
        assertThat(unwatched).isEmpty()
    }

    private fun video(channelId: String) =
        io.github.aedev.flow.data.model.Video(
            id = "v1",
            title = "t",
            channelName = "c",
            channelId = channelId,
            thumbnailUrl = "",
            duration = 60,
            viewCount = 1,
            uploadDate = "",
        )

    @Test
    fun `surfaces a loader failure as an error result`() =
        runTest {
            val loader = RecordingLoader(listOf(SearchFixture.ALL_SAM_SULEK)).apply { failNext = true }

            val result = source(loader).load(PagingSource.LoadParams.Refresh(null, 20, false))

            assertThat(result).isInstanceOf(PagingSource.LoadResult.Error::class.java)
        }

    @Test
    fun `never resumes from a stale key`() =
        runTest {
            assertThat(source(RecordingLoader(listOf(SearchFixture.ALL_SAM_SULEK))).getRefreshKey(mockState())).isNull()
        }

    private fun List<SearchResultItem>.videoIds() = filterIsInstance<SearchResultItem.VideoResult>().map { it.video.id }

    private fun JsonObject.stripShortIds() =
        toSearchResultsPage()
            .sections
            .filterIsInstance<SearchSection.Strip>()
            .filter { it.shelf.style == FeedShelfStyle.Grid }
            .flatMap { strip -> strip.shelf.items.mapNotNull { it.videoOrNull()?.id } }
            .distinct()

    private fun mockState() = androidx.paging.PagingState<String, SearchResultItem>(emptyList(), null, PAGING_CONFIG, 0)

    private companion object {
        val PAGING_CONFIG = androidx.paging.PagingConfig(pageSize = 20)
    }
}
