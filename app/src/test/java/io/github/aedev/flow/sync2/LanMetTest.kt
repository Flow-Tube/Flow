package io.github.aedev.flow.sync2

import androidx.datastore.preferences.core.mutablePreferencesOf
import io.github.aedev.flow.sync2.lan.metIn
import io.github.aedev.flow.sync2.lan.noteMet
import org.junit.Assert.assertEquals
import org.junit.Test

/** When this device last synced with each device on its network, kept per group. */
class LanMetTest {
    @Test
    fun the_latest_session_with_each_device_is_kept() {
        val prefs = mutablePreferencesOf()
        prefs.noteMet("g", "a", 1_000)
        prefs.noteMet("g", "b", 2_000)
        prefs.noteMet("g", "a", 3_000)
        assertEquals(mapOf("a" to 3_000L, "b" to 2_000L), prefs.metIn("g"))
    }

    @Test
    fun another_group_reads_nothing_and_its_first_session_drops_the_old_times() {
        val prefs = mutablePreferencesOf()
        prefs.noteMet("old", "a", 1_000)
        assertEquals(emptyMap<String, Long>(), prefs.metIn("new"))
        prefs.noteMet("new", "b", 2_000)
        assertEquals(mapOf("b" to 2_000L), prefs.metIn("new"))
        assertEquals(emptyMap<String, Long>(), prefs.metIn("old"))
    }

    @Test
    fun nothing_is_known_before_any_session() {
        assertEquals(emptyMap<String, Long>(), mutablePreferencesOf().metIn("g"))
    }
}
