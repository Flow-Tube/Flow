package io.github.aedev.flow.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListReorderTest {
    private val list = listOf("a", "b", "c", "d")

    @Test
    fun `moves an item up to the target key`() {
        assertEquals(listOf("d", "a", "b", "c"), list.movedByKey("d", "a") { it })
    }

    @Test
    fun `moves an item down to the target key`() {
        assertEquals(listOf("b", "c", "a", "d"), list.movedByKey("a", "c") { it })
    }

    @Test
    fun `ignores a missing key or the same position`() {
        assertNull(list.movedByKey("x", "a") { it })
        assertNull(list.movedByKey("a", "header") { it })
        assertNull(list.movedByKey("b", "b") { it })
    }

    @Test
    fun `holds while the database still has the old order`() {
        assertTrue(holdsPendingOrder(saved = list, pending = listOf("b", "a", "c", "d")))
    }

    @Test
    fun `shows the database once it has the saved order`() {
        assertFalse(holdsPendingOrder(saved = list, pending = list))
    }

    @Test
    fun `shows the database when its items changed`() {
        assertFalse(holdsPendingOrder(saved = listOf("a", "b", "c"), pending = listOf("b", "a", "c", "d")))
        assertFalse(holdsPendingOrder(saved = listOf("a", "b", "e", "d"), pending = listOf("b", "a", "c", "d")))
    }

    @Test
    fun `shows the database when nothing is pending`() {
        assertFalse(holdsPendingOrder(saved = list, pending = null))
    }
}
