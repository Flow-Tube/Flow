package io.github.aedev.flow.data.music.artwork

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.aedev.flow.data.local.safePreferencesDataStore
import io.github.aedev.flow.data.profile.StoreScope
import kotlinx.coroutines.flow.first

/** A lookup answered before: the loop, or null when the album has none, and when it was asked. */
internal data class CachedLoop(
    val loop: AnimatedLoop?,
    val atMs: Long,
)

private const val SEPARATOR = "|"

internal fun CachedLoop.encoded(): String =
    if (loop != null) {
        listOf(atMs.toString(), loop.square.orEmpty(), loop.tall.orEmpty()).joinToString(SEPARATOR)
    } else {
        listOf(atMs.toString(), MISS_AFTER_RELEASE_SEARCH).joinToString(SEPARATOR)
    }

/**
 * Older formats read as nothing saved, so the album is asked once more: entries from before tall
 * loops, and misses from before a song's other releases were searched.
 */
internal fun decodeCachedLoop(stored: String): CachedLoop? {
    val parts = stored.split(SEPARATOR)
    val atMs = parts[0].toLongOrNull() ?: return null
    return when {
        parts.size == MISS_PARTS && parts[1] == MISS_AFTER_RELEASE_SEARCH -> {
            CachedLoop(null, atMs)
        }

        parts.size == LOOP_PARTS -> {
            val square = parts[1].ifEmpty { null }
            val tall = parts[2].ifEmpty { null }
            if (square == null && tall == null) null else CachedLoop(AnimatedLoop(square, tall), atMs)
        }

        else -> {
            null
        }
    }
}

private const val LOOP_PARTS = 3
private const val MISS_PARTS = 2
private const val MISS_AFTER_RELEASE_SEARCH = "miss"

internal interface AnimatedArtworkCache {
    suspend fun get(key: String): CachedLoop?

    suspend fun put(
        key: String,
        loop: CachedLoop,
    )

    suspend fun remove(key: String)
}

private val Context.animatedArtworkStore by safePreferencesDataStore(name = "animated_artwork", scope = StoreScope.DEVICE)

/** Answers kept across launches, so the service is asked about an album once in [MAX_ENTRIES]. */
internal class DataStoreAnimatedArtworkCache(
    private val context: Context,
) : AnimatedArtworkCache {
    override suspend fun get(key: String): CachedLoop? {
        val stored = context.animatedArtworkStore.data.first()[stringPreferencesKey(key)] ?: return null
        return decodeCachedLoop(stored)
    }

    override suspend fun put(
        key: String,
        loop: CachedLoop,
    ) {
        context.animatedArtworkStore.edit { preferences ->
            preferences[stringPreferencesKey(key)] = loop.encoded()
            val overflow = preferences.asMap().size - MAX_ENTRIES
            if (overflow > 0) {
                preferences
                    .asMap()
                    .entries
                    .sortedBy { (it.value as? String)?.substringBefore(SEPARATOR)?.toLongOrNull() ?: 0L }
                    .take(overflow)
                    .forEach { preferences -= it.key }
            }
        }
    }

    override suspend fun remove(key: String) {
        context.animatedArtworkStore.edit { it.remove(stringPreferencesKey(key)) }
    }

    private companion object {
        const val MAX_ENTRIES = 600
    }
}
