package io.github.aedev.flow.data.wellbeing

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.dataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * The daily limit's settings and today's count, in the profile's own settings store next to the break reminder. The
 * settings go into a settings backup like the reminders do; the count does not, and neither is synced.
 */
class DailyLimitStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        val settings: Flow<DailyLimitSettings> =
            context.dataStore.data.map { prefs ->
                DailyLimitSettings(
                    enabled = prefs[ENABLED] ?: false,
                    minutes = DailyLimitPolicy.validMinutes(prefs[MINUTES]),
                    countMusic = prefs[COUNT_MUSIC] ?: false,
                )
            }

        /** The stored count, or null before anything was counted. */
        val tally: Flow<DailyTally?> =
            context.dataStore.data.map { prefs ->
                prefs[TALLY_DAY]?.let { day ->
                    DailyTally(
                        day = day,
                        playedMs = prefs[TALLY_PLAYED_MS] ?: 0L,
                        extraMs = prefs[TALLY_EXTRA_MS] ?: 0L,
                        warned = prefs[TALLY_WARNED] ?: false,
                        reached = prefs[TALLY_REACHED] ?: false,
                    )
                }
            }

        suspend fun setSettings(settings: DailyLimitSettings) {
            context.dataStore.edit { prefs ->
                prefs[ENABLED] = settings.enabled
                prefs[MINUTES] = DailyLimitPolicy.validMinutes(settings.minutes)
                prefs[COUNT_MUSIC] = settings.countMusic
            }
        }

        suspend fun setTally(tally: DailyTally) {
            context.dataStore.edit { prefs ->
                prefs[TALLY_DAY] = tally.day
                prefs[TALLY_PLAYED_MS] = tally.playedMs
                prefs[TALLY_EXTRA_MS] = tally.extraMs
                prefs[TALLY_WARNED] = tally.warned
                prefs[TALLY_REACHED] = tally.reached
            }
        }

        companion object {
            private val ENABLED = booleanPreferencesKey("daily_limit")
            private val MINUTES = intPreferencesKey("daily_limit_minutes")
            private val COUNT_MUSIC = booleanPreferencesKey("daily_limit_music")
            private val TALLY_DAY = longPreferencesKey("daily_tally_day")
            private val TALLY_PLAYED_MS = longPreferencesKey("daily_tally_played_ms")
            private val TALLY_EXTRA_MS = longPreferencesKey("daily_tally_extra_ms")
            private val TALLY_WARNED = booleanPreferencesKey("daily_tally_warned")
            private val TALLY_REACHED = booleanPreferencesKey("daily_tally_reached")

            /** The keys a settings backup carries. */
            val SETTING_NAMES: Set<String> = setOf(ENABLED.name, MINUTES.name, COUNT_MUSIC.name)
        }
    }
