package io.github.aedev.flow.data.shorts

import android.content.ComponentCallbacks2
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TrimOnMemoryPressureTest {
    private var trims = 0
    private val callbacks = TrimOnMemoryPressure { trims++ }

    @Test
    fun `leaving the screen keeps the Shorts caches`() {
        callbacks.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)

        assertThat(trims).isEqualTo(0)
    }

    @Test
    fun `a cached process empties them`() {
        callbacks.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)

        assertThat(trims).isEqualTo(1)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `low memory empties them`() {
        callbacks.onLowMemory()

        assertThat(trims).isEqualTo(1)
    }
}
