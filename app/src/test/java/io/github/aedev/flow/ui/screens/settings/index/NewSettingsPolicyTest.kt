package io.github.aedev.flow.ui.screens.settings.index

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.settings.SettingEntry
import io.github.aedev.flow.ui.components.settings.SettingsDestination
import org.junit.Test

class NewSettingsPolicyTest {
    private val today = 20_400L
    private val buffer =
        SettingEntry(key = "buffer.cache_size", title = R.string.buffer_settings_title, destination = SettingsDestination.BUFFER)
    private val theme = SettingEntry(key = "theme.follow", title = R.string.settings_item_theme, destination = SettingsDestination.THEME)

    @Test
    fun `the first run marks every existing key as always known, so nothing is new`() {
        val recorded = NewSettingsPolicy.record(null, listOf(buffer.key, theme.key), today)

        assertThat(recorded).containsExactly(buffer.key, NewSettingsPolicy.ALWAYS_KNOWN, theme.key, NewSettingsPolicy.ALWAYS_KNOWN)
        assertThat(NewSettingsPolicy.newKeys(recorded, listOf(buffer, theme), today, days = 7)).isEmpty()
    }

    @Test
    fun `a key that appears later is dated today, keeps its date and leaves with the index`() {
        val known = mapOf(theme.key to NewSettingsPolicy.ALWAYS_KNOWN, "gone.key" to 5L)

        val recorded = NewSettingsPolicy.record(known, listOf(buffer.key, theme.key), today)
        assertThat(recorded).containsExactly(buffer.key, today, theme.key, NewSettingsPolicy.ALWAYS_KNOWN)

        assertThat(NewSettingsPolicy.record(recorded, listOf(buffer.key, theme.key), today + 3)).isEqualTo(recorded)
    }

    @Test
    fun `a new option marks itself and every page row above it, for the chosen number of days`() {
        val firstSeen = mapOf(buffer.key to today, theme.key to NewSettingsPolicy.ALWAYS_KNOWN)
        val entries = listOf(buffer, theme)

        assertThat(NewSettingsPolicy.newKeys(firstSeen, entries, today + 6, days = 7))
            .containsExactly(buffer.key, "dest.playback", "dest.buffer")
        assertThat(NewSettingsPolicy.newKeys(firstSeen, entries, today + 7, days = 7)).isEmpty()
    }

    @Test
    fun `off, or before Settings was ever opened, marks nothing`() {
        val firstSeen = mapOf(buffer.key to today)

        assertThat(NewSettingsPolicy.newKeys(firstSeen, listOf(buffer), today, days = 0)).isEmpty()
        assertThat(NewSettingsPolicy.newKeys(null, listOf(buffer), today, days = 7)).isEmpty()
    }

    @Test
    fun `every settings key in the index is unique, so first-seen dates cannot collide`() {
        val keys = SettingsIndex.all.map { it.key }
        assertThat(keys).containsNoDuplicates()
    }
}
