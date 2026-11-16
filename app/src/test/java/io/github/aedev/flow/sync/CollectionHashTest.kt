package io.github.aedev.flow.sync

import io.github.aedev.flow.sync.protocol.CollectionWire
import io.github.aedev.flow.sync.protocol.SyncSerialization
import org.junit.Assert.assertEquals
import org.junit.Test

class CollectionHashTest {
    private fun joined(lines: List<String>) = SyncSerialization.sha256Hex(lines.joinToString("\n"))

    @Test
    fun matches_the_joined_hash() {
        val lines = listOf("""{"videoId":"a"}""", """{"videoId":"b"}""", "", """{"videoId":"c"}""")
        assertEquals(joined(lines), SyncSerialization.collectionHash(lines))
    }

    @Test
    fun matches_the_joined_hash_for_no_lines_and_one_line() {
        assertEquals(joined(emptyList()), SyncSerialization.collectionHash(emptyList()))
        assertEquals(SyncSerialization.sha256Hex(""), SyncSerialization.collectionHash(emptyList()))
        assertEquals(SyncSerialization.sha256Hex("x"), SyncSerialization.collectionHash(listOf("x")))
    }

    @Test
    fun matches_the_joined_hash_for_non_latin_text() {
        val lines = listOf("""{"title":"तुम ही हो"}""", """{"title":"日本語のタイトル"}""", """{"title":"🎵 ñandú"}""")
        assertEquals(joined(lines), SyncSerialization.collectionHash(lines))
    }

    @Test
    fun golden_playlist_hash_is_unchanged() {
        val line =
            """{"createdAtMs":1781000000000,"deleted":false,"description":"","isMusic":false,"isProtected":false,"isUserCreated":true,"items":[{"addedAtMs":1,"channelId":"uc","channelName":"c","deleted":false,"durationSeconds":212,"hlc":"100:0:aaa","isMusic":false,"position":0,"thumbnailUrl":"","title":"A","videoId":"v1"}],"origin":"local","syncId":"p1","title":"Gym","updatedHlc":"100:0:aaa","youtubeId":null}"""
        assertEquals(
            "a0534d548eb59e88a7125e803f5d743e55d626bbca45a8622c57d44086e1b528",
            SyncSerialization.collectionHash(listOf(line)),
        )
    }

    @Test
    fun wire_hash_is_worked_out_from_its_lines_unless_given() {
        val lines = (0 until 5_000).map { """{"title":"t","videoId":"v$it"}""" }
        assertEquals(joined(lines), CollectionWire(lines, lines.size).hash)
        assertEquals("given", CollectionWire(lines, lines.size, "given").hash)
    }
}
