package io.github.aedev.flow.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * A setting added to the general store must be sorted into the backup or out of it on purpose:
 * the three Appearance switches of #2037 were added to the store and to neither hand-written list.
 */
class SettingsBackupCoverageTest {
    private val sourceRoot = File("src/main/java/io/github/aedev/flow")

    private fun declaredKeyNames(vararg paths: String): Set<String> =
        paths
            .flatMap { path ->
                KeyDeclaration.findAll(File(sourceRoot, path).readText()).map { it.groupValues[1] }.toList()
            }.toSet()

    @Test
    fun `every key in the general store is backed up or excluded with a reason`() {
        val declared = declaredKeyNames("data/local/LocalDataManager.kt", "data/wellbeing/DailyLimitStore.kt")
        val classified = LocalDataManager.BACKED_UP_NAMES + LocalDataManager.NOT_BACKED_UP.keys

        assertThat(declared).isNotEmpty()
        assertThat(declared - classified).isEmpty()
    }

    @Test
    fun `no key is both backed up and excluded`() {
        assertThat(LocalDataManager.BACKED_UP_NAMES.intersect(LocalDataManager.NOT_BACKED_UP.keys)).isEmpty()
    }

    @Test
    fun `the appearance switches travel in a backup`() {
        assertThat(LocalDataManager.BACKED_UP_NAMES)
            .containsAtLeast("skeleton_gradient", "marquee_enabled", "classic_loading_indicator")
    }

    @Test
    fun `names other stores restore are not player settings too`() {
        val playerNames = declaredKeyNames("data/local/PlayerPreferences.kt")
        val ownedElsewhere =
            LocalDataManager.BACKED_UP_NAMES + SearchHistoryRepository.SETTING_NAMES + AppFontPreferences.SETTING_NAMES

        assertThat(playerNames).isNotEmpty()
        assertThat(playerNames.intersect(ownedElsewhere)).isEmpty()
    }

    @Test
    fun `stored values come back under their own name and type`() {
        val stored =
            mapOf(
                "skeleton_gradient" to true,
                "break_frequency" to 45,
                "theme_mode" to "DARK",
                "subtitle_font_size" to 18.5f,
                "subs_last_refresh_time" to 1_700_000_000_000L,
                "hidden_library_sections" to setOf("playlists"),
            )

        val prefs = mutablePreferencesOf()
        prefs.putAll(settingsBackupOf(stored))

        assertThat(prefs[booleanPreferencesKey("skeleton_gradient")]).isTrue()
        assertThat(prefs[intPreferencesKey("break_frequency")]).isEqualTo(45)
        assertThat(prefs[stringPreferencesKey("theme_mode")]).isEqualTo("DARK")
        assertThat(prefs[floatPreferencesKey("subtitle_font_size")]).isEqualTo(18.5f)
        assertThat(prefs[longPreferencesKey("subs_last_refresh_time")]).isEqualTo(1_700_000_000_000L)
        assertThat(prefs[stringSetPreferencesKey("hidden_library_sections")]).containsExactly("playlists")
    }

    @Test
    fun `filtering keeps only the named values in every type`() {
        val backup =
            settingsBackupOf(mapOf("skeleton_gradient" to true, "theme_mode" to "DARK", "break_frequency" to 30))

        val kept = backup.filterNames { it != "theme_mode" }

        assertThat(kept.booleans).containsExactly("skeleton_gradient", true)
        assertThat(kept.ints).containsExactly("break_frequency", 30)
        assertThat(kept.strings).isEmpty()
    }

    private companion object {
        val KeyDeclaration = Regex("""PreferencesKey\(\s*"([^"]+)"\s*\)""")
    }
}
