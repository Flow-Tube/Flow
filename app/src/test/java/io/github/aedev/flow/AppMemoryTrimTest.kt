package io.github.aedev.flow

import android.content.ComponentCallbacks2
import com.google.common.truth.Truth.assertThat
import org.junit.Test

@Suppress("DEPRECATION")
class AppMemoryTrimTest {
    @Test
    fun `leaving the screen keeps caches and connections`() {
        assertThat(AppMemoryTrim.releasesVolatileMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)).isFalse()
    }

    @Test
    fun `running levels keep caches and connections`() {
        listOf(
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
        ).forEach { assertThat(AppMemoryTrim.releasesVolatileMemory(it)).isFalse() }
    }

    @Test
    fun `a system running low drops refetchable caches but not on every exit`() {
        assertThat(AppMemoryTrim.releasesRefetchableCaches(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)).isFalse()
        assertThat(AppMemoryTrim.releasesRefetchableCaches(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)).isFalse()
        assertThat(AppMemoryTrim.releasesRefetchableCaches(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)).isTrue()
        assertThat(AppMemoryTrim.releasesRefetchableCaches(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)).isTrue()
        assertThat(AppMemoryTrim.releasesRefetchableCaches(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)).isTrue()
    }

    @Test
    fun `a cached process releases them`() {
        listOf(
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
        ).forEach { assertThat(AppMemoryTrim.releasesVolatileMemory(it)).isTrue() }
    }
}
