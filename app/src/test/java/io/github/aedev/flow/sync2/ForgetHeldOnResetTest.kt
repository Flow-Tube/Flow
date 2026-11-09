package io.github.aedev.flow.sync2

import androidx.sqlite.SQLiteConnection
import io.github.aedev.flow.sync2.data.ForgetHeldOnReset
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ForgetHeldOnResetTest {
    @Test
    fun `a reset database takes the held store with it`() {
        val root = createTempDirectory("linked_store").toFile()
        File(root, "kept").mkdirs()
        File(root, "kept/batch").writeText("held")
        File(root, "roster/1").apply { parentFile?.mkdirs() }.writeText("entry")

        ForgetHeldOnReset(root).onDestructiveMigration(mockk<SQLiteConnection>())

        assertFalse(root.exists())
    }

    @Test
    fun `a store that was never written is fine`() {
        val parent = createTempDirectory("linked").toFile()
        val root = File(parent, "linked_store")

        ForgetHeldOnReset(root).onDestructiveMigration(mockk<SQLiteConnection>())

        assertFalse(root.exists())
        assertTrue(parent.exists())
    }
}
