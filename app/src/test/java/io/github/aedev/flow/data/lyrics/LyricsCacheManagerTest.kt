package io.github.aedev.flow.data.lyrics

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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
}
