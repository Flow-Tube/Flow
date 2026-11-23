package io.github.aedev.flow.data.video.downloader.work

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.video.downloader.transfer.RangeDownloader
import io.github.aedev.flow.data.video.downloader.transfer.StreamRole
import io.github.aedev.flow.data.video.downloader.transfer.TransferStream
import org.junit.Test
import java.io.File

class DownloadSpaceNeedTest {
    private val block = RangeDownloader.BLOCK_SIZE

    @Test
    fun `a fresh download needs its parts and the joined file`() {
        val parts = listOf(stream(StreamRole.VIDEO, 10 * block), stream(StreamRole.AUDIO, 2 * block))

        assertThat(bytesToFit(parts)).isEqualTo(24 * block)
    }

    @Test
    fun `blocks already on disk are not counted twice`() {
        val video = stream(StreamRole.VIDEO, 10 * block).apply { completedBlocks.addAll(0 until 4) }
        video.partialBlockBytes[4] = 100L

        assertThat(bytesToFit(listOf(video))).isEqualTo(6 * block - 100L + 10 * block)
    }

    private fun stream(
        role: StreamRole,
        size: Long,
    ) = TransferStream(role, "https://example.com", File("unused"), 1, size)
}
