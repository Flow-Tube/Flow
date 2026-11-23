package io.github.aedev.flow.data.local

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ResumableQueueFileTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val store: File get() = File(folder.root, "music_queue.preferences_pb")

    private fun resumable() = ResumableQueueFile(store)

    @Test
    fun `no store means nothing to resume`() {
        assertThat(resumable().canResume()).isFalse()
    }

    @Test
    fun `a store reset to empty after corruption is not resumable`() {
        store.createNewFile()

        assertThat(resumable().canResume()).isFalse()
    }

    @Test
    fun `a store written before this check existed still resumes`() {
        store.writeBytes(byteArrayOf(1, 2, 3))

        assertThat(resumable().canResume()).isTrue()
    }

    @Test
    fun `a failed read stops resuming until the next save`() {
        store.writeBytes(byteArrayOf(1, 2, 3))
        val resumable = resumable()

        resumable.markUnreadable()
        assertThat(resumable.canResume()).isFalse()

        resumable.markReadable()
        assertThat(resumable.canResume()).isTrue()
    }

    @Test
    fun `marking an empty store unreadable leaves no marker behind`() {
        val resumable = resumable()

        resumable.markUnreadable()

        assertThat(folder.root.listFiles()).isEmpty()
    }
}
