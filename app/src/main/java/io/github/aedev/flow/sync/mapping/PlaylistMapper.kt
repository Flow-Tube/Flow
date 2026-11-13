package io.github.aedev.flow.sync.mapping

import io.github.aedev.flow.data.local.entity.PlaylistEntity
import io.github.aedev.flow.data.local.entity.PlaylistVideoCrossRef
import io.github.aedev.flow.data.local.entity.VideoEntity
import io.github.aedev.flow.sync.canonical.CanonicalPlaylist
import io.github.aedev.flow.sync.canonical.CanonicalPlaylistItem

/**
 * Playlist ⇄ canonical mapping. Handles the Android specifics:
 * - cross-ref `position` is a sort key (negative `currentTimeMillis` for newest-first); canonical
 *   `position` is a 0-based ascending display rank — so we sort by native position ASC and number
 *   from 0.
 * - the reserved Watch Later playlist (id `watch_later`) maps to the cross-platform reserved
 *   syncId; saved external (YouTube) playlists carry `origin=youtube` + `youtubeId`.
 */
object PlaylistMapper {
    const val WATCH_LATER_ID = "watch_later"
    const val SAVED_SHORTS_ID = "saved_shorts"

    /** One playlist member: its cross-ref (position) joined to the video metadata if present. */
    data class ItemSource(
        val crossRef: PlaylistVideoCrossRef,
        val video: VideoEntity?,
    )

    fun toCanonical(
        playlist: PlaylistEntity,
        items: List<ItemSource>,
        exportHlc: String,
    ): CanonicalPlaylist {
        val origin = if (!playlist.isUserCreated) CanonicalPlaylist.ORIGIN_YOUTUBE else CanonicalPlaylist.ORIGIN_LOCAL
        val syncId =
            when (playlist.id) {
                WATCH_LATER_ID -> CanonicalPlaylist.RESERVED_WATCH_LATER
                else -> playlist.syncId ?: playlist.id
            }
        // Sort by native cross-ref position ASC (= display order), then assign canonical rank 0..n.
        val ranked =
            items.sortedBy { it.crossRef.position }.mapIndexed { idx, src ->
                val v = src.video
                CanonicalPlaylistItem(
                    videoId = src.crossRef.videoId,
                    position = idx.toLong(),
                    addedAtMs = if (src.crossRef.position < 0) -src.crossRef.position else 0L,
                    deleted = false,
                    title = v?.title ?: "",
                    channelName = v?.channelName ?: "",
                    channelId = v?.channelId ?: "",
                    thumbnailUrl = v?.thumbnailUrl ?: "",
                    durationSeconds = (v?.duration ?: 0).toLong(),
                    isMusic = v?.isMusic ?: playlist.isMusic,
                    hlc = exportHlc,
                )
            }
        return CanonicalPlaylist(
            syncId = syncId,
            origin = origin,
            youtubeId = if (origin == CanonicalPlaylist.ORIGIN_YOUTUBE) playlist.id else null,
            title = playlist.name,
            description = playlist.description,
            isMusic = playlist.isMusic,
            isUserCreated = playlist.isUserCreated,
            isProtected = playlist.id == WATCH_LATER_ID,
            createdAtMs = playlist.createdAt,
            updatedHlc = exportHlc,
            deleted = false,
            items = ranked,
        )
    }

    /** Build the Android playlist row for a merged canonical playlist targeting [localId]. */
    fun toPlaylistEntity(
        c: CanonicalPlaylist,
        localId: String,
    ): PlaylistEntity =
        PlaylistEntity(
            id = localId,
            name = c.title,
            description = c.description,
            thumbnailUrl = coverThumbnail(c),
            isPrivate = c.isProtected,
            createdAt = if (c.createdAtMs > 0) c.createdAtMs else System.currentTimeMillis(),
            videoCount = c.items.count { !it.deleted },
            isMusic = c.isMusic,
            isUserCreated = c.isUserCreated,
            syncId = if (localId == WATCH_LATER_ID) null else c.syncId,
        )

    private fun coverThumbnail(c: CanonicalPlaylist): String =
        c.items
            .filter { !it.deleted }
            .minByOrNull { it.position }
            ?.thumbnailUrl
            .orEmpty()

    /** Cross-refs for a merged canonical playlist; canonical rank → ascending Android position. */
    fun toCrossRefs(
        c: CanonicalPlaylist,
        localId: String,
    ): List<PlaylistVideoCrossRef> =
        c.items.filter { !it.deleted }.map {
            PlaylistVideoCrossRef(playlistId = localId, videoId = it.videoId, position = it.position)
        }

    /** Stub video rows so the cross-ref FK is satisfied and the playlist shows item metadata. */
    fun toVideoEntities(c: CanonicalPlaylist): List<VideoEntity> =
        c.items.filter { !it.deleted }.map {
            VideoEntity(
                id = it.videoId,
                title = it.title,
                channelName = it.channelName,
                channelId = it.channelId,
                thumbnailUrl = it.thumbnailUrl,
                duration = it.durationSeconds.toInt(),
                viewCount = 0L,
                uploadDate = "",
                description = "",
                channelThumbnailUrl = "",
                isMusic = it.isMusic,
            )
        }
}
