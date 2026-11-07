package io.github.aedev.flow.ui.screens.music

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.music.model.MusicItemType
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.recommendation.MusicSection
import io.github.aedev.flow.innertube.models.BrowseEndpoint
import io.github.aedev.flow.innertube.pages.HomePage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MusicHomeChipFeedTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val requested = mutableListOf<String>()
    private val responses = mutableMapOf<String, CompletableDeferred<Pair<List<MusicSection>, String?>>>()
    private val moreRequested = mutableListOf<String>()
    private var morePages: Pair<List<MusicSection>, String?> = emptyList<MusicSection>() to null

    private val feed =
        MusicHomeChipFeed(
            scope = scope,
            dispatcher = dispatcher,
            loadPage = { chip ->
                requested += chip.title
                responses.getOrPut(chip.title) { CompletableDeferred() }.await()
            },
            loadMorePage = { continuation ->
                moreRequested += continuation
                morePages
            },
            prepareSections = { it },
        )

    private fun chip(title: String) = HomePage.Chip(title, BrowseEndpoint("FEmusic_home", "params-$title"), null)

    private fun section(title: String) = MusicSection(title = title, tracks = listOf(track(title)))

    private fun track(id: String) =
        MusicTrack(videoId = id, title = id, artist = "", thumbnailUrl = "", duration = 200, itemType = MusicItemType.SONG)

    private fun answer(
        title: String,
        vararg sections: String,
        continuation: String? = null,
    ) {
        responses.getOrPut(title) { CompletableDeferred() }.complete(sections.map(::section) to continuation)
        scope.runCurrent()
    }

    private fun sectionTitles() =
        feed.page.value
            ?.sections
            ?.map { it.title }

    @Test
    fun `selecting a chip loads its page and then shows it`() {
        feed.select(chip("Energize"))
        scope.runCurrent()
        assertThat(feed.page.value?.status).isEqualTo(MusicHomeChipStatus.LOADING)

        answer("Energize", "Power boost", continuation = "next")

        assertThat(feed.page.value?.status).isEqualTo(MusicHomeChipStatus.READY)
        assertThat(sectionTitles()).containsExactly("Power boost")
        assertThat(feed.page.value?.continuation).isEqualTo("next")
    }

    @Test
    fun `the last tap wins even if an older answer arrives after it`() {
        val relax = chip("Relax")
        feed.select(relax)
        scope.runCurrent()
        val slowRelax = responses.getValue("Relax")
        feed.select(chip("Workout"))
        scope.runCurrent()

        answer("Workout", "In the gym")
        slowRelax.complete(listOf(section("Chilled")) to null)
        scope.runCurrent()

        assertThat(
            feed.page.value
                ?.chip
                ?.title,
        ).isEqualTo("Workout")
        assertThat(sectionTitles()).containsExactly("In the gym")
    }

    @Test
    fun `turning a chip off clears the page without a request`() {
        feed.select(chip("Energize"))
        scope.runCurrent()
        answer("Energize", "Power boost")
        requested.clear()

        feed.select(null)
        scope.runCurrent()

        assertThat(feed.page.value).isNull()
        assertThat(requested).isEmpty()
    }

    @Test
    fun `tapping a chip again reads its cached page`() {
        feed.select(chip("Energize"))
        scope.runCurrent()
        answer("Energize", "Power boost")
        feed.select(null)
        requested.clear()

        feed.select(chip("Energize"))
        scope.runCurrent()

        assertThat(requested).isEmpty()
        assertThat(feed.page.value?.status).isEqualTo(MusicHomeChipStatus.READY)
        assertThat(sectionTitles()).containsExactly("Power boost")
    }

    @Test
    fun `a failure shows as failed and retry asks again`() {
        feed.select(chip("Energize"))
        scope.runCurrent()
        responses.getValue("Energize").completeExceptionally(IllegalStateException("offline"))
        scope.runCurrent()
        assertThat(feed.page.value?.status).isEqualTo(MusicHomeChipStatus.FAILED)

        responses.remove("Energize")
        requested.clear()
        feed.retry()
        scope.runCurrent()
        answer("Energize", "Power boost")

        assertThat(requested).containsExactly("Energize")
        assertThat(feed.page.value?.status).isEqualTo(MusicHomeChipStatus.READY)
    }

    @Test
    fun `a failed chip is not cached`() {
        feed.select(chip("Energize"))
        scope.runCurrent()
        responses.getValue("Energize").completeExceptionally(IllegalStateException("offline"))
        scope.runCurrent()
        feed.select(null)
        responses.remove("Energize")
        requested.clear()

        feed.select(chip("Energize"))
        scope.runCurrent()

        assertThat(requested).containsExactly("Energize")
    }

    @Test
    fun `load more follows the chip's own continuation and appends`() {
        feed.select(chip("Energize"))
        scope.runCurrent()
        answer("Energize", "Power boost", continuation = "energize-2")
        morePages = listOf(section("Hip-hop energy")) to "energize-3"

        feed.loadMore()
        scope.runCurrent()

        assertThat(moreRequested).containsExactly("energize-2")
        assertThat(sectionTitles()).containsExactly("Power boost", "Hip-hop energy").inOrder()
        assertThat(feed.page.value?.continuation).isEqualTo("energize-3")
        assertThat(feed.page.value?.isMoreLoading).isFalse()
    }

    @Test
    fun `a page loaded further keeps those shelves when reopened`() {
        feed.select(chip("Energize"))
        scope.runCurrent()
        answer("Energize", "Power boost", continuation = "energize-2")
        morePages = listOf(section("Hip-hop energy")) to null
        feed.loadMore()
        scope.runCurrent()
        feed.select(null)

        feed.select(chip("Energize"))

        assertThat(sectionTitles()).containsExactly("Power boost", "Hip-hop energy").inOrder()
    }

    @Test
    fun `load more without a continuation asks for nothing`() {
        feed.select(chip("Energize"))
        scope.runCurrent()
        answer("Energize", "Power boost")

        feed.loadMore()
        scope.runCurrent()

        assertThat(moreRequested).isEmpty()
    }

    @Test
    fun `refresh with a chip on reloads that chip and keeps its shelves meanwhile`() {
        feed.select(chip("Energize"))
        scope.runCurrent()
        answer("Energize", "Power boost")
        responses.remove("Energize")
        requested.clear()

        assertThat(feed.refresh()).isTrue()
        scope.runCurrent()
        assertThat(requested).containsExactly("Energize")
        assertThat(feed.page.value?.status).isEqualTo(MusicHomeChipStatus.REFRESHING)
        assertThat(sectionTitles()).containsExactly("Power boost")

        answer("Energize", "Fresh shelf")
        assertThat(sectionTitles()).containsExactly("Fresh shelf")
    }

    @Test
    fun `a failed refresh keeps what was on screen`() {
        feed.select(chip("Energize"))
        scope.runCurrent()
        answer("Energize", "Power boost")
        responses.remove("Energize")

        feed.refresh()
        scope.runCurrent()
        responses.getValue("Energize").completeExceptionally(IllegalStateException("offline"))
        scope.runCurrent()

        assertThat(feed.page.value?.status).isEqualTo(MusicHomeChipStatus.READY)
        assertThat(sectionTitles()).containsExactly("Power boost")
    }

    @Test
    fun `refresh with no chip reports it so the normal feed reloads`() {
        assertThat(feed.refresh()).isFalse()
    }

    @Test
    fun `reset drops the selection and every cached page`() {
        feed.select(chip("Energize"))
        scope.runCurrent()
        answer("Energize", "Power boost")
        responses.remove("Energize")
        requested.clear()

        feed.reset()
        assertThat(feed.page.value).isNull()

        feed.select(chip("Energize"))
        scope.runCurrent()
        assertThat(requested).containsExactly("Energize")
    }

    @Test
    fun `a chip with no endpoint shows the normal feed`() {
        feed.select(HomePage.Chip("All", null, null))
        scope.runCurrent()

        assertThat(feed.page.value).isNull()
        assertThat(requested).isEmpty()
    }

    @Test
    fun `only song shelves are ranked, and a shelf left empty goes`() =
        runTest {
            val playlists = MusicSection(title = "Power boost", tracks = listOf(track("p").copy(itemType = MusicItemType.PLAYLIST)))
            val songs = MusicSection(title = "Quick picks", tracks = listOf(track("a"), track("b")))
            val blocked = MusicSection(title = "Blocked", tracks = listOf(track("x")))

            val ranked =
                listOf(playlists, songs, blocked).rankSongShelves { tracks ->
                    tracks.filterNot { it.videoId == "x" }.reversed()
                }

            assertThat(ranked.map { it.title }).containsExactly("Power boost", "Quick picks").inOrder()
            assertThat(ranked[1].tracks.map { it.videoId }).containsExactly("b", "a").inOrder()
        }

    @Test
    fun `only a shelf of songs reads as rows`() {
        assertThat(MusicSection(title = "Quick picks", tracks = listOf(track("a"), track("b"))).isSongShelf).isTrue()
        assertThat(
            MusicSection(
                title = "Power boost",
                tracks = listOf(track("a"), track("p").copy(itemType = MusicItemType.PLAYLIST)),
            ).isSongShelf,
        ).isFalse()
        assertThat(MusicSection(title = "Videos", tracks = listOf(track("v").copy(isVideoSong = true))).isSongShelf).isFalse()
        assertThat(MusicSection(title = "Mixed", tracks = listOf(track("a"), track("v").copy(isVideoSong = true))).isSongShelf).isTrue()
    }
}
