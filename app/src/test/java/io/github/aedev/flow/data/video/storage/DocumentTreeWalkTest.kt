package io.github.aedev.flow.data.video.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentTreeWalkTest {
    private fun file(name: String) = TreeDocument(id = name, name = name, isFolder = false)

    private fun folder(name: String) = TreeDocument(id = name, name = name, isFolder = true)

    private val tree =
        mapOf(
            "root" to listOf(file("a.mp4"), folder("Album"), file(".trashed-1-b.mp4"), folder(".hidden")),
            "Album" to listOf(file("01.m4a"), folder("Nested")),
            "Nested" to listOf(file("deep.mp4")),
            ".hidden" to listOf(file("secret.mp4")),
        )

    private fun walk(
        maxDepth: Int = 2,
        limit: Int = 100,
        listed: MutableList<String> = mutableListOf(),
    ) = filesInTree("root", maxDepth, limit) { id ->
        listed += id
        tree[id].orEmpty()
    }.map { it.name }

    @Test
    fun `finds files in the folder and in the folders directly inside it`() {
        assertEquals(listOf("a.mp4", "01.m4a"), walk())
    }

    @Test
    fun `a deeper limit reaches nested folders`() {
        assertEquals(listOf("a.mp4", "01.m4a", "deep.mp4"), walk(maxDepth = 3))
    }

    @Test
    fun `depth one lists only the folder itself`() {
        val listed = mutableListOf<String>()
        assertEquals(listOf("a.mp4"), walk(maxDepth = 1, listed = listed))
        assertEquals(listOf("root"), listed)
    }

    @Test
    fun `hidden files and folders are skipped and never opened`() {
        val listed = mutableListOf<String>()
        val found = walk(maxDepth = 3, listed = listed)
        assertTrue(found.none { it.startsWith(".") || it == "secret.mp4" })
        assertTrue(".hidden" !in listed)
    }

    @Test
    fun `the walk stops after the document limit`() {
        val listed = mutableListOf<String>()
        assertEquals(listOf("a.mp4"), walk(maxDepth = 3, limit = 2, listed = listed))
        assertEquals(listOf("root"), listed)
    }
}
