package io.github.aedev.flow.ui.components.music.section

import org.junit.Assert.assertEquals
import org.junit.Test

class MusicShelfKeysTest {
    @Test
    fun `distinct shelves keep their own key`() {
        assertEquals(listOf("dynamic:A", "dynamic:B"), uniqueItemKeys(listOf("dynamic:A", "dynamic:B")))
    }

    @Test
    fun `a repeated title gets a numbered key instead of a duplicate`() {
        assertEquals(
            listOf("dynamic:Mix", "dynamic:Mix#2", "dynamic:Mix#3"),
            uniqueItemKeys(listOf("dynamic:Mix", "dynamic:Mix", "dynamic:Mix")),
        )
    }

    @Test
    fun `a title that looks numbered still never collides`() {
        val keys = uniqueItemKeys(listOf("dynamic:Mix", "dynamic:Mix", "dynamic:Mix#2"))

        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `a shelf added above does not move the keys below`() {
        val before = uniqueItemKeys(listOf("dynamic:B", "dynamic:C"))
        val after = uniqueItemKeys(listOf("dynamic:A", "dynamic:B", "dynamic:C"))

        assertEquals(before, after.drop(1))
    }
}
