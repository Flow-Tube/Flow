package io.github.aedev.flow.ui.screens.music

import android.util.Log
import io.github.aedev.flow.data.music.model.MusicItemType
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.recommendation.MusicSection
import io.github.aedev.flow.innertube.pages.HomePage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

enum class MusicHomeChipStatus { LOADING, REFRESHING, READY, FAILED }

/** What a selected home chip shows in place of the normal feed. */
data class MusicHomeChipPage(
    val chip: HomePage.Chip,
    val status: MusicHomeChipStatus = MusicHomeChipStatus.LOADING,
    val sections: List<MusicSection> = emptyList(),
    val continuation: String? = null,
    val isMoreLoading: Boolean = false,
)

/** A shelf of songs reads as rows, even with a video among them; playlists, albums and all-video shelves stay as cards. */
internal val MusicSection.isSongShelf: Boolean
    get() = tracks.all { it.itemType == MusicItemType.SONG } && tracks.any { !it.isVideoSong }

/** Song shelves get the normal feed's taste pass, which also drops blocked artists. */
internal suspend fun List<MusicSection>.rankSongShelves(rank: suspend (List<MusicTrack>) -> List<MusicTrack>): List<MusicSection> =
    mapNotNull { section ->
        if (!section.isSongShelf) return@mapNotNull section
        rank(section.tracks).takeIf { it.isNotEmpty() }?.let { section.copy(tracks = it) }
    }

/**
 * The selected chip's page, apart from the normal feed so turning the chip off costs no request.
 * Only the newest request may publish, and pages stay cached until [refresh] or [reset].
 */
internal class MusicHomeChipFeed(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val loadPage: suspend (HomePage.Chip) -> Pair<List<MusicSection>, String?>,
    private val loadMorePage: suspend (String) -> Pair<List<MusicSection>, String?>,
    private val prepareSections: suspend (List<MusicSection>) -> List<MusicSection>,
) {
    private val _page = MutableStateFlow<MusicHomeChipPage?>(null)
    val page: StateFlow<MusicHomeChipPage?> = _page.asStateFlow()

    private val cache = ConcurrentHashMap<String, MusicHomeChipPage>()
    private var loadJob: Job? = null
    private var moreJob: Job? = null

    fun select(chip: HomePage.Chip?) {
        cancelRequests()
        if (chip?.endpoint == null) {
            _page.value = null
            return
        }
        val cached = cache[chip.title]
        if (cached != null) {
            _page.value = cached
        } else {
            _page.value = MusicHomeChipPage(chip)
            fetch(chip)
        }
    }

    fun retry() {
        val current = _page.value ?: return
        cancelRequests()
        _page.value = MusicHomeChipPage(current.chip)
        fetch(current.chip)
    }

    /** Forgets every cached page and reloads the selected one. False when no chip is selected. */
    fun refresh(): Boolean {
        cache.clear()
        val current = _page.value ?: return false
        cancelRequests()
        _page.value =
            current.copy(
                status = if (current.sections.isEmpty()) MusicHomeChipStatus.LOADING else MusicHomeChipStatus.REFRESHING,
                isMoreLoading = false,
            )
        fetch(current.chip)
        return true
    }

    /** Chips belong to a language and region, so a change of either drops them all. */
    fun reset() {
        cache.clear()
        select(null)
    }

    fun loadMore() {
        val current = _page.value ?: return
        val continuation = current.continuation ?: return
        if (current.isMoreLoading || current.status != MusicHomeChipStatus.READY) return
        _page.value = current.copy(isMoreLoading = true)
        moreJob =
            scope.launch(dispatcher) {
                val (loaded, next) =
                    try {
                        loadMorePage(continuation).let { (sections, next) -> prepareSections(sections) to next }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Loading more of ${current.chip.title} failed", e)
                        emptyList<MusicSection>() to null
                    }
                publish(current.chip) {
                    it.copy(sections = it.sections + loaded, continuation = next, isMoreLoading = false)
                }
            }
    }

    private fun fetch(chip: HomePage.Chip) {
        loadJob =
            scope.launch(dispatcher) {
                val loaded =
                    try {
                        loadPage(chip).let { (sections, next) -> prepareSections(sections) to next }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Loading ${chip.title} failed", e)
                        null
                    }
                publish(chip) { current ->
                    when {
                        loaded != null -> {
                            current.copy(
                                status = MusicHomeChipStatus.READY,
                                sections = loaded.first,
                                continuation = loaded.second,
                            )
                        }

                        current.sections.isNotEmpty() -> {
                            current.copy(status = MusicHomeChipStatus.READY)
                        }

                        else -> {
                            current.copy(status = MusicHomeChipStatus.FAILED)
                        }
                    }
                }
            }
    }

    private fun publish(
        chip: HomePage.Chip,
        change: (MusicHomeChipPage) -> MusicHomeChipPage,
    ) {
        _page.update { current ->
            if (current?.chip?.title != chip.title) {
                current
            } else {
                change(current).also { if (it.status == MusicHomeChipStatus.READY) cache[chip.title] = it }
            }
        }
    }

    private fun cancelRequests() {
        loadJob?.cancel()
        moreJob?.cancel()
    }

    private companion object {
        const val TAG = "MusicHomeChipFeed"
    }
}
