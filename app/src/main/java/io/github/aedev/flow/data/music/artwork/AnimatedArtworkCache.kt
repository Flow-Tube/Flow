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

internal fun CachedLoop.encoded(): String = listOf(atMs.toString(), loop?.square.orEmpty(), loop?.tall.orEmpty()).joinToString(SEPARATOR)

/** Entries from before tall loops have no third part. They read as a miss, so the album is asked once more. */
internal fun decodeCachedLoop(stored: String): CachedLoop? {
    val parts = stored.split(SEPARATOR)
    if (parts.size != ENCODED_PARTS) return null
    val atMs = parts[0].toLongOrNull() ?: return null
    val square = parts[1].ifEmpty { null }
    val tall = parts[2].ifEmpty { null }
    return CachedLoop(if (square == null && tall == null) null else AnimatedLoop(square, tall), atMs)
}

private const val ENCODED_PARTS = 3

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
