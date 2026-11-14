package io.github.aedev.flow.data.contentfilter.aislist

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AiListStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val list =
        StoredAiList(
            version = 4,
            etag = "\"e4\"",
            updatedAtMs = 99L,
            block = AiListEntries(handles = listOf("alpha", "beta"), ids = listOf("UCaaaaaaaaaaaaaaaaaaaaaa")),
            warn = AiListEntries(handles = listOf("gamma")),
        )

    @Test
    fun `a committed list loads back unchanged`() {
        val directory = File(folder.root, "aislist")
        AiListStore(directory).commit(list)

        assertThat(AiListStore(directory).load()).isEqualTo(list)
        assertThat(AiListStore(directory).exists()).isTrue()
    }

    @Test
    fun `an unfinished write beside the file does not replace the stored list`() {
        val directory = File(folder.root, "aislist")
        AiListStore(directory).commit(list)
        File(directory, "list.json.new").writeText("{ half written")

        assertThat(AiListStore(directory).load()).isEqualTo(list)
    }

    @Test
    fun `a device with no list loads nothing`() {
        val store = AiListStore(File(folder.root, "empty"))

        assertThat(store.load()).isNull()
        assertThat(store.exists()).isFalse()
    }
}
