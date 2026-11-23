package io.github.aedev.flow.data.repository

import io.github.aedev.flow.player.queue.QueuePage
import io.github.aedev.flow.player.queue.QueuePageSource
import io.github.aedev.flow.player.queue.QueueSourceSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Builds the sources that keep a playlist queue loading, and rebuilds them from a saved queue. */
@Singleton
class PlaylistQueueSources
    @Inject
    constructor(
        private val playlists: YouTubePlaylistRepository,
        private val repository: YouTubeRepository,
    ) {
        /** The playlist's pages from browse continuation [token]. */
        fun playlistPages(
            playlistId: String,
            token: String,
        ): QueuePageSource = PlaylistPagesSource(playlistId, token, playlists)

        /** The watch panel's pages from its continuation [token]. */
        fun watchPanel(
            playlistId: String,
            token: String,
        ): QueuePageSource = WatchPanelSource(playlistId, token, repository)

        fun from(spec: QueueSourceSpec): QueuePageSource =
            when (spec) {
                is QueueSourceSpec.PlaylistPages -> playlistPages(spec.playlistId, spec.token)
                is QueueSourceSpec.WatchPanel -> watchPanel(spec.playlistId, spec.token)
            }
    }

private class PlaylistPagesSource(
    private val playlistId: String,
    private var token: String,
    private val playlists: YouTubePlaylistRepository,
) : QueuePageSource {
    override val spec: QueueSourceSpec get() = QueueSourceSpec.PlaylistPages(playlistId, token)

    override suspend fun next(): QueuePage? {
        val (videos, next) = playlists.nextPage(playlistId, token) ?: return null
        // A token YouTube already sent would read the same page again forever.
        val more = next != null && next != token
        next?.let { token = it }
        return QueuePage(videos, hasMore = more)
    }
}

private class WatchPanelSource(
    private val playlistId: String,
    private var token: String,
    private val repository: YouTubeRepository,
) : QueuePageSource {
    override val spec: QueueSourceSpec get() = QueueSourceSpec.WatchPanel(playlistId, token)

    override suspend fun next(): QueuePage? {
        val (videos, next) = repository.watchPlaylistContinuation(token) ?: return null
        val more = next != null && next != token
        next?.let { token = it }
        return QueuePage(videos, hasMore = more)
    }
}
