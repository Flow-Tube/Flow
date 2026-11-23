package io.github.aedev.flow.ui.screens.playlists

import android.util.Log
import io.github.aedev.flow.data.local.PlaylistRepository
import io.github.aedev.flow.data.local.SavedPlaylistCheck
import io.github.aedev.flow.data.local.SavedPlaylistSyncStore
import io.github.aedev.flow.data.repository.RemotePlaylistPage
import io.github.aedev.flow.data.repository.YouTubePlaylistRepository
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.time.Instant

private const val TAG = "SavedPlaylistRefresh"

/** The header a saved playlist's page shows once it has been checked; blank fields keep what the page has. */
internal data class SavedPlaylistHeader(
    val title: String = "",
    val ownerName: String? = null,
    val description: String = "",
    val thumbnailUrl: String = "",
    val hidesUnavailable: Boolean = false,
)

/**
 * Keeps a saved playlist's stored copy in step with YouTube. Within [SavedPlaylistRefreshTtl] of the
 * last check nothing is requested; after that the first page is compared with the copy, and every
 * page is loaded and stored only when it differs.
 */
internal class SavedPlaylistRefresh(
    private val playlistId: String,
    private val playlists: PlaylistRepository,
    private val remote: YouTubePlaylistRepository,
    private val checks: SavedPlaylistSyncStore,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** The header to show, or null when YouTube could not be reached or the copy could not be stored. */
    suspend fun run(): SavedPlaylistHeader? {
        val savedIds = playlists.getPlaylistVideoIds(playlistId)
        val last = lastCheck()
        val now = clock.instant()
        if (last != null && isSavedCopyFresh(Instant.ofEpochMilli(last.checkedAtMillis), now)) {
            return SavedPlaylistHeader(ownerName = last.ownerName, hidesUnavailable = hides(last.headerCount, savedIds.size))
        }
        val first = remote.cachedComplete(playlistId) ?: remote.firstPage(playlistId) ?: return null
        val unchanged =
            firstPageMatchesSavedCopy(
                savedIds = savedIds,
                firstPageIds = first.videos.map { it.id },
                hasMorePages = first.continuation != null,
                headerCount = first.videoCount,
                lastHeaderCount = last?.headerCount,
            )
        var storedCount = savedIds.size
        if (!unchanged) {
            val complete = remote.completeFrom(playlistId, first) ?: return first.header(storedCount)
            try {
                playlists.syncSavedPlaylistVideos(playlistId, complete.videos)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Saved copy of $playlistId not refreshed", e)
                return null
            }
            storedCount = complete.videos.size
        }
        record(SavedPlaylistCheck(now.toEpochMilli(), first.videoCount, first.ownerName))
        return first.header(storedCount)
    }

    private suspend fun lastCheck(): SavedPlaylistCheck? =
        try {
            checks.lastCheck(playlistId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Last check of $playlistId unreadable", e)
            null
        }

    private suspend fun record(check: SavedPlaylistCheck) {
        try {
            checks.recordCheck(playlistId, check)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Check of $playlistId not recorded", e)
        }
    }

    private fun hides(
        headerCount: Int?,
        storedCount: Int,
    ) = headerCount != null && headerCount > storedCount

    private fun RemotePlaylistPage.header(storedCount: Int) =
        SavedPlaylistHeader(
            title = title,
            ownerName = ownerName,
            description = description,
            thumbnailUrl = thumbnailUrl,
            hidesUnavailable = hidesUnavailable(storedCount),
        )
}
