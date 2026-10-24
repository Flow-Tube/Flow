package io.github.aedev.flow.data.download

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import java.io.IOException

/** Plain JVM: the data source reads only a URI's scheme, so mocks stand in for the framework's Uri. */
class ChunkStitchingDataSourceTest {
    /** Serves [song] in pieces of at most [pieceLength] bytes per open, as the resolver's chunks do. */
    private class ChunkedSong(
        private val song: ByteArray,
        private val pieceLength: Int,
        private val openedUri: Uri,
        private val failFrom: Long = Long.MAX_VALUE,
    ) : DataSource {
        val openedAt = mutableListOf<Long>()
        private var position = 0L
        private var remaining = 0L

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            if (dataSpec.position >= failFrom) throw IOException("offline")
            openedAt += dataSpec.position
            position = dataSpec.position
            remaining = minOf(pieceLength.toLong(), song.size - position)
            return remaining
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
            val count = minOf(length.toLong(), remaining).toInt()
            song.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
            position += count
            remaining -= count
            return count
        }

        override fun getUri(): Uri = openedUri

        override fun close() = Unit
    }

    private val song = ByteArray(25) { it.toByte() }

    private fun uri(scheme: String): Uri = mockk { every { this@mockk.scheme } returns scheme }

    private fun spec(
        scheme: String = "music",
        length: Long = C.LENGTH_UNSET.toLong(),
    ) = DataSpec
        .Builder()
        .setUri(uri(scheme))
        .setKey("abc")
        .setLength(length)
        .build()

    private fun DataSource.readAll(): ByteArray {
        val out = mutableListOf<Byte>()
        val buffer = ByteArray(4)
        while (true) {
            val read = read(buffer, 0, buffer.size)
            if (read == C.RESULT_END_OF_INPUT) return out.toByteArray()
            out += buffer.take(read)
        }
    }

    @Test
    fun `a streamed song reads on across its chunks as one stream`() {
        val upstream = ChunkedSong(song, pieceLength = 10, openedUri = uri("https"))
        val source = ChunkStitchingDataSource(upstream)

        assertThat(source.open(spec())).isEqualTo(C.LENGTH_UNSET.toLong())
        assertThat(source.readAll()).isEqualTo(song)
        assertThat(upstream.openedAt).containsExactly(0L, 10L, 20L, 25L).inOrder()
    }

    @Test
    fun `a failed open past the last chunk ends the song where it stands`() {
        val upstream = ChunkedSong(song, pieceLength = 10, openedUri = uri("music"), failFrom = 25)
        val source = ChunkStitchingDataSource(upstream)

        source.open(spec())

        assertThat(source.readAll()).isEqualTo(song)
    }

    @Test
    fun `a song on the device is read as it is`() {
        val upstream = ChunkedSong(song, pieceLength = 10, openedUri = uri("file"))
        val source = ChunkStitchingDataSource(upstream)

        assertThat(source.open(spec())).isEqualTo(10L)
        assertThat(source.readAll()).hasLength(10)
        assertThat(upstream.openedAt).containsExactly(0L)
    }

    @Test
    fun `a bounded request is not carried on`() {
        val upstream = ChunkedSong(song, pieceLength = 10, openedUri = uri("https"))
        val source = ChunkStitchingDataSource(upstream)

        assertThat(source.open(spec(length = 10))).isEqualTo(10L)
        assertThat(source.readAll()).hasLength(10)
        assertThat(upstream.openedAt).containsExactly(0L)
    }
}
