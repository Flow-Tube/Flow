package io.github.aedev.flow.data.lyrics

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LyricsCacheManagerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context by lazy { mockk<Context> { every { filesDir } returns folder.root } }
    private val found = listOf(LyricsEntry(1L, "found"))
    private val picked = listOf(LyricsEntry(1L, "picked"))

    @Test
    fun `lyrics picked by hand are not replaced by a provider`() =
        runTest {
            LyricsCacheManager.saveManualLyrics(context, "v", picked)

            val saved = LyricsCacheManager.saveLyrics(context, "v", found)

            assertThat(saved).isFalse()
            assertThat(LyricsCacheManager.getLyrics(context, "v")).isEqualTo(picked)
        }

    @Test
    fun `clearing the mark lets a provider save again`() =
        runTest {
            LyricsCacheManager.saveManualLyrics(context, "v", picked)
            LyricsCacheManager.clearManual(context, "v")

            assertThat(LyricsCacheManager.isManual(context, "v")).isFalse()
            assertThat(LyricsCacheManager.saveLyrics(context, "v", found)).isTrue()
            assertThat(LyricsCacheManager.getLyrics(context, "v")).isEqualTo(found)
        }

    @Test
    fun `one song's mark does not touch another song`() =
        runTest {
            LyricsCacheManager.saveManualLyrics(context, "v", picked)

            assertThat(LyricsCacheManager.saveLyrics(context, "w", found)).isTrue()
        }

    @Test
    fun `an upgrade look is due a week after the file was written or looked at`() =
        runTest {
            LyricsCacheManager.saveLyrics(context, "v", found)
            val savedAt = File(folder.root, "lyrics/v.json").lastModified()

            assertThat(LyricsCacheManager.upgradeIsDue(context, "v", nowMs = savedAt + 6 * DAY_MS)).isFalse()
            assertThat(LyricsCacheManager.upgradeIsDue(context, "v", nowMs = savedAt + 7 * DAY_MS)).isTrue()

            LyricsCacheManager.markUpgradeLookDone(context, "v", nowMs = savedAt + 7 * DAY_MS)

            assertThat(LyricsCacheManager.upgradeIsDue(context, "v", nowMs = savedAt + 8 * DAY_MS)).isFalse()
        }

    @Test
    fun `the one time pass keeps every file and makes a look due on the next play`() =
        runTest {
            LyricsCacheManager.saveLyrics(context, "typed", found)

            LyricsCacheManager.recheckUnverifiedOnce(context) { false }

            assertThat(LyricsCacheManager.getLyrics(context, "typed")).isEqualTo(found)
            assertThat(LyricsCacheManager.upgradeIsDue(context, "typed")).isTrue()
            assertThat(LyricsCacheManager.isUnverified(context, "typed")).isTrue()

            LyricsCacheManager.markUpgradeLookDone(context, "typed")

            assertThat(LyricsCacheManager.getLyrics(context, "typed")).isEqualTo(found)
            assertThat(LyricsCacheManager.isUnverified(context, "typed")).isFalse()
        }

    @Test
    fun `the one time pass leaves hand picks and what it should keep`() =
        runTest {
            val kept = listOf(LyricsEntry(1L, "kept"))
            LyricsCacheManager.saveLyrics(context, "word", kept)
            LyricsCacheManager.saveManualLyrics(context, "picked", picked)

            LyricsCacheManager.recheckUnverifiedOnce(context) { it == kept }

            assertThat(LyricsCacheManager.upgradeIsDue(context, "word")).isFalse()
            assertThat(LyricsCacheManager.upgradeIsDue(context, "picked")).isFalse()
        }

    @Test
    fun `the one time pass runs once`() =
        runTest {
            LyricsCacheManager.recheckUnverifiedOnce(context) { false }
            LyricsCacheManager.saveLyrics(context, "later", found)

            LyricsCacheManager.recheckUnverifiedOnce(context) { false }

            assertThat(LyricsCacheManager.upgradeIsDue(context, "later")).isFalse()
        }

    @Test
    fun `a failing marker write does not fail the lookup that started it`() =
        runTest {
            val blocked = mockk<Context> { every { filesDir } returns File(folder.root, "file-not-dir").apply { writeText("") } }

            LyricsCacheManager.recheckUnverifiedOnce(blocked) { false }
        }

    @Test
    fun `a sync offset outlives the lyrics and goes only when cleared`() =
        runTest {
            LyricsCacheManager.saveLyrics(context, "v", found)
            LyricsCacheManager.saveSyncOffset(context, "v", -750L)

            LyricsCacheManager.evictLyrics(context, "v")
            LyricsCacheManager.saveLyrics(context, "v", picked)

            assertThat(LyricsCacheManager.syncOffset(context, "v")).isEqualTo(-750L)

            LyricsCacheManager.clearSyncOffset(context, "v")

            assertThat(LyricsCacheManager.syncOffset(context, "v")).isNull()
        }

    @Test
    fun `a song never nudged has no sync offset`() =
        runTest {
            assertThat(LyricsCacheManager.syncOffset(context, "v")).isNull()
        }

    @Test
    fun `no lyrics is remembered for a week`() =
        runTest {
            LyricsCacheManager.rememberNoLyrics(context, "v")
            val rememberedAt = File(folder.root, "lyrics/v.none").lastModified()

            assertThat(LyricsCacheManager.remembersNoLyrics(context, "v", nowMs = rememberedAt + 6 * DAY_MS)).isTrue()
            assertThat(LyricsCacheManager.remembersNoLyrics(context, "v", nowMs = rememberedAt + 7 * DAY_MS)).isFalse()
        }

    @Test
    fun `forgetting no lyrics or saving some ends the memory of it`() =
        runTest {
            LyricsCacheManager.rememberNoLyrics(context, "v")
            LyricsCacheManager.forgetNoLyrics(context, "v")
            assertThat(LyricsCacheManager.remembersNoLyrics(context, "v")).isFalse()

            LyricsCacheManager.rememberNoLyrics(context, "w")
            LyricsCacheManager.saveLyrics(context, "w", found)
            assertThat(LyricsCacheManager.remembersNoLyrics(context, "w")).isFalse()
        }

    @Test
    fun `a song never looked up is not remembered as having no lyrics`() =
        runTest {
            assertThat(LyricsCacheManager.remembersNoLyrics(context, "v")).isFalse()
        }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
