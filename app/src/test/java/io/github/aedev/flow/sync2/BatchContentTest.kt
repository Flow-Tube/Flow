package io.github.aedev.flow.sync2

import io.github.aedev.flow.sync.protocol.SyncSerialization
import io.github.aedev.flow.sync2.changes.batchContent
import io.github.aedev.flow.sync2.crypto.encodePayload
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class BatchContentTest {
    private fun joined(
        collection: String,
        head: String,
        link: String?,
        lines: List<String>,
    ): ByteArray {
        val header =
            SyncSerialization.canonicalJson(
                buildJsonObject {
                    put("collection", JsonPrimitive(collection))
                    put("head", JsonPrimitive(head))
                    put("link", JsonPrimitive(link))
                },
            )
        return (listOf(header) + lines).joinToString("\n").toByteArray()
    }

    @Test
    fun same_bytes_as_joining_the_lines() {
        val lines = listOf("""{"videoId":"a"}""", """{"title":"日本語 🎵","videoId":"b"}""")
        assertArrayEquals(joined("watch_history", "h1", "L1", lines), batchContent("watch_history", "h1", "L1", lines))
        assertArrayEquals(joined("profiles", "h1", null, lines), batchContent("profiles", "h1", null, lines))
    }

    @Test
    fun same_bytes_with_no_records() {
        assertArrayEquals(joined("likes", "h", "L", emptyList()), batchContent("likes", "h", "L", emptyList()))
    }

    @Test
    fun same_payload_after_compression() {
        val lines = (0 until 2_000).map { """{"title":"t$it","videoId":"v$it"}""" }
        assertArrayEquals(
            encodePayload(joined("watch_history", "h", "L", lines)),
            encodePayload(batchContent("watch_history", "h", "L", lines)),
        )
    }
}
