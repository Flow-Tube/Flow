package io.github.aedev.flow.data.video.downloader.tags

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.inspector.MetadataRetriever
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.utils.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Reads the tags Flow embedded into a download (see [Mp4Remuxer] and [Mp4TagWriter]) back from a
 * file or content Uri. Files without Flow ids still yield their title, artist, album and cover,
 * falling back to the platform retriever when Media3 finds nothing.
 */
@OptIn(UnstableApi::class)
class DownloadTagReader
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        suspend fun read(uri: Uri): EmbeddedTags? =
            withContext(Dispatchers.IO) {
                val embedded = EmbeddedTags.fromEntries(entries(uri))
                if (embedded.flow != null) return@withContext embedded
                val platform = readWithPlatform(uri)
                val merged =
                    platform?.let { embedded.withFallback(it.title, it.artist, it.album, it.cover) } ?: embedded
                merged.takeIf { tags ->
                    tags.cover != null ||
                        listOf(tags.title, tags.artist, tags.album, tags.description, tags.comment, tags.lyrics).any { it != null }
                }
            }

        /** Every metadata entry Media3 parses from the file's container, from all its tracks; empty on failure. */
        internal suspend fun entries(uri: Uri): List<Metadata.Entry> =
            withContext(Dispatchers.IO) { withTimeoutOrNull(TIMEOUT_MS) { retrieveEntries(uri) }.orEmpty() }

        private suspend fun retrieveEntries(uri: Uri): List<Metadata.Entry> =
            try {
                MetadataRetriever.Builder(context, MediaItem.fromUri(uri)).build().use { retriever ->
                    retriever.retrieveTrackGroups().await().metadataEntries()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Media3 metadata read failed for $uri: ${e.message}")
                emptyList()
            }

        private fun readWithPlatform(uri: Uri): EmbeddedTags? {
            val retriever = MediaMetadataRetriever()
            return try {
                if (uri.scheme == null || uri.scheme == "file") retriever.setDataSource(uri.path) else retriever.setDataSource(context, uri)
                EmbeddedTags(
                    flow = null,
                    title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                    artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                    album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                    cover = retriever.embeddedPicture,
                )
            } catch (e: Exception) {
                Log.w(TAG, "Platform metadata read failed for $uri: ${e.message}")
                null
            } finally {
                runCatching { retriever.release() }
            }
        }

        private companion object {
            const val TAG = "DownloadTagReader"
            const val TIMEOUT_MS = 10_000L
        }
    }
