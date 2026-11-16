package io.github.aedev.flow.data.local

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey

/** Stored values as a backup, each in the map for its type. A value of any other type is not a setting and stays out. */
internal fun settingsBackupOf(entries: Map<String, Any>): SettingsBackup {
    val strings = mutableMapOf<String, String>()
    val booleans = mutableMapOf<String, Boolean>()
    val ints = mutableMapOf<String, Int>()
    val floats = mutableMapOf<String, Float>()
    val longs = mutableMapOf<String, Long>()
    val stringSets = mutableMapOf<String, Set<String>>()
    entries.forEach { (name, value) ->
        when (value) {
            is String -> strings[name] = value
            is Boolean -> booleans[name] = value
            is Int -> ints[name] = value
            is Float -> floats[name] = value
            is Long -> longs[name] = value
            is Set<*> -> stringSets[name] = value.filterIsInstance<String>().toSet()
        }
    }
    return SettingsBackup(strings, booleans, ints, floats, longs, stringSets)
}

/** The part of this backup whose names pass [predicate]. */
internal fun SettingsBackup.filterNames(predicate: (String) -> Boolean): SettingsBackup =
    SettingsBackup(
        strings.filterKeys(predicate),
        booleans.filterKeys(predicate),
        ints.filterKeys(predicate),
        floats.filterKeys(predicate),
        longs.filterKeys(predicate),
        stringSets.filterKeys(predicate),
    )

/** Writes every value in [backup] under its own name and type. */
internal fun MutablePreferences.putAll(backup: SettingsBackup) {
    backup.strings.forEach { (k, v) -> this[stringPreferencesKey(k)] = v }
    backup.booleans.forEach { (k, v) -> this[booleanPreferencesKey(k)] = v }
    backup.ints.forEach { (k, v) -> this[intPreferencesKey(k)] = v }
    backup.floats.forEach { (k, v) -> this[floatPreferencesKey(k)] = v }
    backup.longs.forEach { (k, v) -> this[longPreferencesKey(k)] = v }
    backup.stringSets.forEach { (k, v) -> this[stringSetPreferencesKey(k)] = v }
}
