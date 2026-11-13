package io.github.aedev.flow.ui.components.shared

import androidx.compose.ui.unit.Constraints
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FittedShareCardTest {
    @Test
    fun `a card wider than the space shrinks to its width`() {
        assertThat(fitScale(width = 1080, height = 1080, maxWidth = 945, maxHeight = 2000)).isWithin(1e-6f).of(0.875f)
    }

    @Test
    fun `a tall card shrinks to the height when that is tighter`() {
        assertThat(fitScale(width = 1080, height = 1920, maxWidth = 1000, maxHeight = 960)).isWithin(1e-6f).of(0.5f)
    }

    @Test
    fun `a card that fits is never enlarged`() {
        assertThat(fitScale(width = 300, height = 200, maxWidth = 1000, maxHeight = 1000)).isEqualTo(1f)
    }

    @Test
    fun `unbounded space keeps the full size`() {
        assertThat(fitScale(width = 1080, height = 1920, maxWidth = 945, maxHeight = Constraints.Infinity)).isWithin(1e-6f).of(0.875f)
    }

    @Test
    fun `an empty card keeps scale 1`() {
        assertThat(fitScale(width = 0, height = 0, maxWidth = 100, maxHeight = 100)).isEqualTo(1f)
    }
}
