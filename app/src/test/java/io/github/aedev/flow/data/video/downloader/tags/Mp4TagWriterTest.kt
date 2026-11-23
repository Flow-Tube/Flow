package io.github.aedev.flow.data.video.downloader.tags

import androidx.media3.common.util.Log
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.container.Mp4Box
import androidx.media3.extractor.mp4.BoxParser
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.video.downloader.tags.Mp4Fixtures.Layout
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class Mp4TagWriterTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val writer = Mp4TagWriter()

    @Before
    fun configureMedia3ForJvm() {
        ParsableByteArray.setShouldEnforceLimitOnLegacyMethods(true)
        Log.setLogLevel(Log.LOG_LEVEL_OFF)
    }

    @After
    fun restoreMedia3Defaults() {
        ParsableByteArray.setShouldEnforceLimitOnLegacyMethods(null)
        Log.setLogLevel(Log.LOG_LEVEL_ALL)
    }

    private val tags =
        DownloadTags(
            kind = DownloadKind.MUSIC,
            videoId = "dQw4w9WgXcQ",
            title = "Never Gonna Give You Up",
            artists = listOf("Rick Astley"),
            channelId = "UCuAXFkgsw1L7xaCfnd5JJOw",
            channelName = "Rick Astley - Topic",
            album = "Whenever You Need Somebody",
            albumId = "MPREb_album",
            albumArtist = "Rick Astley",
            trackNumber = 1,
            trackTotal = 10,
            playlistId = "OLAK5uy_list",
            releaseDate = "1987-11-16",
            description = "Official audio",
            sourceUrl = "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
            viewCount = 1_500_000_000L,
            likeCount = 18_000_000L,
            thumbnailUrl = "https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg",
            lyrics = "We're no strangers to love",
        )

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(256) { it.toByte() }

    @Test
    fun `moov at the end gets udta appended in place and mdat stays put`() {
        val layout = Mp4Fixtures.moovLast()
        val file = write(layout, cover = jpeg)

        val bytes = file.readBytes()
        assertLayoutIntact(layout, bytes)
        assertThat(Mp4Fixtures.parse(bytes).map { it.type }).containsExactly("ftyp", "mdat", "moov").inOrder()
        val ilst = ilst(bytes)
        assertThat(Mp4Fixtures.ilstText(ilst, "©nam")).isEqualTo(tags.title)
        assertThat(Mp4Fixtures.ilstText(ilst, "©ART")).isEqualTo("Rick Astley")
        assertThat(Mp4Fixtures.ilstText(ilst, "©alb")).isEqualTo(tags.album)
        assertThat(Mp4Fixtures.ilstText(ilst, "aART")).isEqualTo(tags.albumArtist)
        assertThat(Mp4Fixtures.ilstText(ilst, "©day")).isEqualTo("1987-11-16")
        assertThat(Mp4Fixtures.ilstText(ilst, "©cmt")).isEqualTo(tags.sourceUrl)
        assertThat(Mp4Fixtures.ilstText(ilst, "desc")).isEqualTo(tags.description)
        assertThat(Mp4Fixtures.ilstText(ilst, "©lyr")).isEqualTo(tags.lyrics)
        assertThat(Mp4Fixtures.ilstText(ilst, "©too")).isEqualTo("Flow")
        val trkn = Mp4Fixtures.ilstData(ilst, "trkn")!!
        assertThat(trkn.first).isEqualTo(0)
        assertThat(trkn.second.map { it.toInt() }).containsExactly(0, 0, 0, 1, 0, 10, 0, 0).inOrder()
        val covr = Mp4Fixtures.ilstData(ilst, "covr")!!
        assertThat(covr.first).isEqualTo(13)
        assertThat(covr.second).isEqualTo(jpeg)
    }

    @Test
    fun `hdlr declares the iTunes mdir handler`() {
        val bytes = write(Mp4Fixtures.moovLast()).readBytes()
        val hdlr = Mp4Fixtures.find(bytes, "moov", "udta", "meta", "hdlr")!!
        assertThat(String(hdlr.payload(), 8, 8, Charsets.ISO_8859_1)).isEqualTo("mdirappl")
    }

    @Test
    fun `moov before mdat moves to the end and leaves a same-size free box`() {
        val layout = Mp4Fixtures.moovFirst()
        val oldMoov = Mp4Fixtures.parse(layout.bytes).first { it.type == "moov" }
        val bytes = write(layout).readBytes()

        assertLayoutIntact(layout, bytes)
        val boxes = Mp4Fixtures.parse(bytes)
        assertThat(boxes.map { it.type }).containsExactly("ftyp", "free", "mdat", "moov").inOrder()
        assertThat(boxes[1].start).isEqualTo(oldMoov.start)
        assertThat(boxes[1].size).isEqualTo(oldMoov.size)
        assertThat(Mp4Fixtures.ilstText(ilst(bytes), "©nam")).isEqualTo(tags.title)
    }

    @Test
    fun `moov before mdat with room beside it is tagged in place and stays at the front`() {
        val layout = Mp4Fixtures.moovFirst(freeAfterMoov = 64 * 1024)
        val bytes = write(layout, cover = jpeg).readBytes()

        assertLayoutIntact(layout, bytes)
        val boxes = Mp4Fixtures.parse(bytes)
        assertThat(boxes.map { it.type }).containsExactly("ftyp", "moov", "free", "mdat").inOrder()
        assertThat(boxes.last().start).isEqualTo(Mp4Fixtures.parse(layout.bytes).last().start)
        assertThat(Mp4Fixtures.ilstData(ilst(bytes), "covr")!!.second).isEqualTo(jpeg)
    }

    @Test
    fun `open-ended mdat is given an explicit size before moov is appended`() {
        val layout = Mp4Fixtures.moovFirst(openEndedMdat = true)
        val bytes = write(layout).readBytes()

        assertLayoutIntact(layout, bytes)
        val mdat = Mp4Fixtures.parse(bytes).first { it.type == "mdat" }
        assertThat(mdat.size).isEqualTo(8L + Mp4Fixtures.MDAT_PAYLOAD.size)
        assertThat(Mp4Fixtures.parse(bytes).last().type).isEqualTo("moov")
    }

    @Test
    fun `largesize mdat and moov headers are handled`() {
        val layout = Mp4Fixtures.moovLast(largeMdat = true, largeMoov = true)
        val bytes = write(layout).readBytes()

        assertLayoutIntact(layout, bytes)
        assertThat(Mp4Fixtures.parse(bytes).first { it.type == "mdat" }.headerSize).isEqualTo(16)
        assertThat(Mp4Fixtures.ilstText(ilst(bytes), "©nam")).isEqualTo(tags.title)
    }

    @Test
    fun `writing twice replaces the ilst instead of duplicating it`() {
        val layout = Mp4Fixtures.moovFirst()
        val file = write(layout)
        writer.write(file, tags.copy(title = "Second title"))
        val sizeAfterSecond = file.length()
        writer.write(file, tags.copy(title = "Second title"))

        val bytes = file.readBytes()
        assertLayoutIntact(layout, bytes)
        assertThat(file.length()).isEqualTo(sizeAfterSecond)
        val moov = Mp4Fixtures.parse(bytes).single { it.type == "moov" }
        assertThat(moov.children().count { it.type == "udta" }).isEqualTo(1)
        val udta = moov.child("udta")!!
        assertThat(udta.children().count { it.type == "meta" }).isEqualTo(1)
        val meta = udta.child("meta")!!
        assertThat(meta.children(4).count { it.type == "ilst" }).isEqualTo(1)
        assertThat(meta.children(4).count { it.type == "hdlr" }).isEqualTo(1)
        val ilst = meta.child("ilst", 4)!!
        assertThat(ilst.children().count { it.type == "©nam" }).isEqualTo(1)
        assertThat(Mp4Fixtures.ilstText(ilst, "©nam")).isEqualTo("Second title")
    }

    @Test
    fun `existing udta children are preserved`() {
        val location = Mp4Fixtures.box("©xyz", "+35.1-15.1/".toByteArray())
        val layout = Mp4Fixtures.moovLast(moovExtras = arrayOf(Mp4Fixtures.box("udta", location)))
        val bytes = write(layout).readBytes()

        val udta = Mp4Fixtures.find(bytes, "moov", "udta")!!
        assertThat(udta.children().map { it.type }).containsExactly("©xyz", "meta").inOrder()
        assertThat(udta.child("©xyz")!!.payload()).isEqualTo("+35.1-15.1/".toByteArray())
    }

    @Test
    fun `oversized or unknown cover is skipped`() {
        val big = jpeg + ByteArray(IlstAtoms.MAX_COVER_BYTES)
        assertThat(Mp4Fixtures.ilstData(ilst(write(Mp4Fixtures.moovLast(), cover = big).readBytes()), "covr")).isNull()
        assertThat(Mp4Fixtures.ilstData(ilst(write(Mp4Fixtures.moovLast(), cover = ByteArray(64)).readBytes()), "covr")).isNull()
    }

    @Test
    fun `png cover uses data type 14`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) + ByteArray(32)
        val covr = Mp4Fixtures.ilstData(ilst(write(Mp4Fixtures.moovLast(), cover = png).readBytes()), "covr")!!
        assertThat(covr.first).isEqualTo(14)
    }

    @Test
    fun `media3 parses the written tags back into the same DownloadTags`() {
        val bytes = write(Mp4Fixtures.moovLast(), cover = jpeg).readBytes()
        val udta = Mp4Fixtures.find(bytes, "moov", "udta")!!
        val metadata =
            BoxParser.parseUdta(
                Mp4Box.LeafBox(Mp4Box.TYPE_udta, ParsableByteArray(udta.bytes.copyOfRange(udta.start, udta.end))),
                false,
            )
        val entries = List(metadata.length()) { metadata[it] }

        val embedded = EmbeddedTags.fromEntries(entries)

        assertThat(embedded.flow).isEqualTo(tags)
        assertThat(embedded.title).isEqualTo(tags.title)
        assertThat(embedded.artist).isEqualTo("Rick Astley")
        assertThat(embedded.album).isEqualTo(tags.album)
        assertThat(embedded.cover).isEqualTo(jpeg)
        assertThat(embedded.lyrics).isEqualTo(tags.lyrics)
    }

    @Test
    fun `synced lyrics survive the round trip line by line`() {
        val lrc = "[00:01.00]We're no strangers to love\n[00:05.50]You know the rules"
        val file = folder.newFile().apply { writeBytes(Mp4Fixtures.moovLast().bytes) }
        writer.write(file, tags.copy(lyrics = lrc))
        val udta = Mp4Fixtures.find(file.readBytes(), "moov", "udta")!!
        val metadata =
            BoxParser.parseUdta(
                Mp4Box.LeafBox(Mp4Box.TYPE_udta, ParsableByteArray(udta.bytes.copyOfRange(udta.start, udta.end))),
                false,
            )

        assertThat(EmbeddedTags.fromEntries(List(metadata.length()) { metadata[it] }).lyrics).isEqualTo(lrc)
    }

    @Test
    fun `file without moov is rejected untouched`() {
        val bytes = Mp4Fixtures.ftyp() + Mp4Fixtures.box("mdat", Mp4Fixtures.MDAT_PAYLOAD)
        val file = folder.newFile("nomoov.mp4").apply { writeBytes(bytes) }

        assertThrows(IOException::class.java) { writer.write(file, tags) }
        assertThat(file.readBytes()).isEqualTo(bytes)
    }

    @Test
    fun `truncated box is rejected`() {
        val bytes = Mp4Fixtures.moovLast().bytes.let { it.copyOf(it.size - 10) }
        val file = folder.newFile("truncated.mp4").apply { writeBytes(bytes) }

        assertThrows(IOException::class.java) { writer.write(file, tags) }
    }

    private fun write(
        layout: Layout,
        cover: ByteArray? = null,
    ): File {
        val file = folder.newFile()
        file.writeBytes(layout.bytes)
        writer.write(file, tags, cover)
        return file
    }

    private fun ilst(bytes: ByteArray): Mp4Fixtures.Box = Mp4Fixtures.find(bytes, "moov", "udta", "meta", "ilst")!!

    private fun assertLayoutIntact(
        original: Layout,
        rewritten: ByteArray,
    ) {
        val boxes = Mp4Fixtures.parse(rewritten)
        assertThat(boxes.sumOf { it.size }).isEqualTo(rewritten.size.toLong())
        val before = Mp4Fixtures.parse(original.bytes).first { it.type == "mdat" }
        val after = boxes.first { it.type == "mdat" }
        assertThat(after.start).isEqualTo(before.start)
        assertThat(after.payload()).isEqualTo(original.mdatPayload)
        val originalOffsets = Mp4Fixtures.chunkOffsets(original.bytes)
        assertThat(Mp4Fixtures.chunkOffsets(rewritten)).isEqualTo(originalOffsets)
        originalOffsets.forEach { offset ->
            assertThat(offset).isAtLeast(after.payloadStart.toLong())
            assertThat(offset).isLessThan(after.end.toLong())
            assertThat(rewritten[offset.toInt()]).isEqualTo(original.bytes[offset.toInt()])
        }
    }
}
