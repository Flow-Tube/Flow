package io.github.aedev.flow.data.playlist

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.Video
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class PlaylistArchiveTest {
    private fun video(
        id: String,
        addedAt: Long? = null,
    ) = Video(
        id = id,
        title = "Video $id",
        channelName = "Channel",
        channelId = "UC123",
        thumbnailUrl = "https://i.ytimg.com/vi/$id/hq720.jpg",
        duration = 245,
        viewCount = 1_000L,
        uploadDate = "",
        timestamp = 0L,
        addedAtInPlaylist = addedAt,
    )

    private fun archive(vararg files: PlaylistArchiveFile): ByteArray =
        ByteArrayOutputStream().also { PlaylistArchive.write(it, files.toList()) }.toByteArray()

    private fun read(bytes: ByteArray): Pair<Boolean, List<Pair<String, Boolean>>> {
        val files = ArrayList<Pair<String, Boolean>>()
        val ok = runBlocking { PlaylistArchive.read(ByteArrayInputStream(bytes)) { text, inArchive -> files += text to inArchive } }
        return ok to files
    }

    private fun entryNames(bytes: ByteArray): List<String> =
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip -> generateSequence { zip.nextEntry }.map { it.name }.toList() }

    private fun file(
        name: String,
        videos: List<Video>,
        isMusic: Boolean = false,
        kind: PlaylistFileKind? = null,
    ) = PlaylistArchiveFile(name, PlaylistFileCodec.encode(name, "About $name", videos, exportedAt = 1L, isMusic, kind))

    @Test
    fun `a saved library reads back with the same playlists, order and counts`() {
        val watchLater =
            listOf(video("aaaaaaaaaaa", addedAt = 30L), video("bbbbbbbbbbb", addedAt = 20L), video("ccccccccccc", addedAt = 10L))
        val road = listOf(video("ddddddddddd"), video("eeeeeeeeeee"))
        val mix = listOf(video("fffffffffff"))
        val bytes =
            archive(
                file("Watch later", watchLater, kind = PlaylistFileKind.WATCH_LATER),
                file("Road trip", road),
                file("Mix", mix, isMusic = true),
                file("Empty", emptyList()),
            )

        val (ok, texts) = read(bytes)

        assertThat(ok).isTrue()
        assertThat(texts.map { it.second }.distinct()).containsExactly(true)
        val files = texts.map { (PlaylistFileCodec.decode(it.first) as PlaylistFileRead.Read).file }
        assertThat(files.map { it.playlist.name }).containsExactly("Watch later", "Road trip", "Mix", "Empty").inOrder()
        assertThat(files.map { it.videos.size }).containsExactly(3, 2, 1, 0).inOrder()
        assertThat(files[0].videos.map { it.id }).containsExactly("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc").inOrder()
        assertThat(files[0].videos.map { it.addedAt }).containsExactly(30L, 20L, 10L).inOrder()
        assertThat(files.map(PlaylistFileKind::of)).containsExactly(PlaylistFileKind.WATCH_LATER, null, null, null).inOrder()
        assertThat(files.map { it.playlist.isMusic }).containsExactly(false, false, true, false).inOrder()
        assertThat(files[1].playlist.description).isEqualTo("About Road trip")
    }

    @Test
    fun `playlists that share a name get their own file in the zip`() {
        val bytes =
            archive(
                file("Mix", listOf(video("aaaaaaaaaaa"))),
                file("Mix", listOf(video("bbbbbbbbbbb"))),
                file("mix", listOf(video("ccccccccccc"))),
                file("A/B", listOf(video("ddddddddddd"))),
            )

        assertThat(entryNames(bytes)).containsExactly("Mix.json", "Mix (2).json", "mix (3).json", "A_B.json").inOrder()
        val (_, texts) = read(bytes)
        val ids =
            texts.map {
                (PlaylistFileCodec.decode(it.first) as PlaylistFileRead.Read)
                    .file.videos
                    .single()
                    .id
            }
        assertThat(ids).containsExactly("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc", "ddddddddddd").inOrder()
    }

    @Test
    fun `a single playlist file reads as itself, not from an archive`() {
        val text = PlaylistFileCodec.encode("Road trip", "", listOf(video("aaaaaaaaaaa")), exportedAt = 1L)

        val (ok, texts) = read(text.toByteArray())

        assertThat(ok).isTrue()
        assertThat(texts).containsExactly(text to false)
    }

    @Test
    fun `a file from before kinds existed reads as an ordinary playlist`() {
        val old = """{"format":"flow-playlist","version":1,"exportedAt":1,"playlist":{"name":"Watch later"},"videos":[]}"""

        val file = (PlaylistFileCodec.decode(old) as PlaylistFileRead.Read).file

        assertThat(PlaylistFileKind.of(file)).isNull()
    }

    @Test
    fun `a list only a newer Flow knows imports as an ordinary playlist`() {
        val newer = """{"format":"flow-playlist","version":1,"exportedAt":1,"playlist":{"name":"x","kind":"history"},"videos":[]}"""

        assertThat(PlaylistFileKind.of((PlaylistFileCodec.decode(newer) as PlaylistFileRead.Read).file)).isNull()
    }

    @Test
    fun `an ordinary playlist file carries no kind field`() {
        val text = PlaylistFileCodec.encode("Road trip", "", listOf(video("aaaaaaaaaaa")), exportedAt = 1L)

        assertThat(text).doesNotContain("\"kind\"")
    }

    @Test
    fun `other entries in a zip are skipped`() {
        val bytes =
            ByteArrayOutputStream()
                .also { out ->
                    ZipOutputStream(out).use { zip ->
                        zip.putNextEntry(ZipEntry("notes.txt"))
                        zip.write("hello".toByteArray())
                        zip.closeEntry()
                        zip.putNextEntry(ZipEntry("folder/"))
                        zip.closeEntry()
                        zip.putNextEntry(ZipEntry("folder/Road trip.JSON"))
                        zip.write(PlaylistFileCodec.encode("Road trip", "", listOf(video("aaaaaaaaaaa")), 1L).toByteArray())
                        zip.closeEntry()
                    }
                }.toByteArray()

        val (ok, texts) = read(bytes)

        assertThat(ok).isTrue()
        assertThat(texts).hasSize(1)
    }

    @Test
    fun `an entry larger than any playlist stops the read`() {
        val bytes =
            ByteArrayOutputStream()
                .also { out ->
                    ZipOutputStream(out).use { zip ->
                        zip.putNextEntry(ZipEntry("big.json"))
                        val chunk = ByteArray(1024 * 1024) { ' '.code.toByte() }
                        repeat((PLAYLIST_FILE_MAX_BYTES / chunk.size).toInt() + 1) { zip.write(chunk) }
                        zip.closeEntry()
                    }
                }.toByteArray()

        val (ok, texts) = read(bytes)

        assertThat(ok).isFalse()
        assertThat(texts).isEmpty()
    }

    @Test
    fun `liked videos keep their dates, and a file without dates keeps its order`() {
        val dated = likesOf(listOf(video("aaaaaaaaaaa", addedAt = 50L), video("bbbbbbbbbbb", addedAt = 40L)), isMusic = true)
        val undated = likesOf(listOf(video("aaaaaaaaaaa"), video("bbbbbbbbbbb"), video("ccccccccccc")), isMusic = false, now = 1_000L)

        assertThat(dated.map { it.likedAt }).containsExactly(50L, 40L).inOrder()
        assertThat(dated.all { it.isMusic }).isTrue()
        assertThat(dated.first().channelId).isEqualTo("UC123")
        assertThat(dated.first().durationSeconds).isEqualTo(245)
        assertThat(undated.map { it.likedAt }).containsExactly(1_000L, 999L, 998L).inOrder()
        assertThat(undated.none { it.isMusic }).isTrue()
    }
}
