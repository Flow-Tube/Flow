package io.github.aedev.flow.player.error

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.safePreferencesDataStore
import io.github.aedev.flow.data.profile.StoreScope
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private val Context.playbackErrorStore by safePreferencesDataStore(name = "playback_errors", scope = StoreScope.DEVICE)

private val ERRORS = stringPreferencesKey("errors")
private const val MAX_ERRORS = 30

/** [line] after [existing], keeping only the newest [MAX_ERRORS]. */
internal fun appendCapped(
    existing: String?,
    line: String,
): String = (existing?.lines().orEmpty() + line).takeLast(MAX_ERRORS).joinToString("\n")

/**
 * The latest music playback failures, kept across restarts: session logs only cover the running
 * process, so a report made after reopening Flow would otherwise hold nothing about them.
 */
@Singleton
class PlaybackErrorLog
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        suspend fun add(
            songId: String,
            errorCode: String,
            retry: Int,
        ) {
            val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US))
            val line = "$time  $songId  $errorCode  retry=$retry"
            context.playbackErrorStore.edit { it[ERRORS] = appendCapped(it[ERRORS], line) }
        }

        suspend fun recent(): String? = context.playbackErrorStore.data.first()[ERRORS]
    }
