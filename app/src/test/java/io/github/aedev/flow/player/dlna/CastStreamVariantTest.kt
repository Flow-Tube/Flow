package io.github.aedev.flow.player.dlna

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.player.stream.model.AudioStream
import io.github.aedev.flow.player.stream.model.ItagInfo
import io.github.aedev.flow.player.stream.model.MediaFormat
import org.junit.Test

class CastStreamVariantTest {
    private fun audioStream(itagItem: ItagInfo?): AudioStream =
        AudioStream(
            id = "140",
            content = "https://example.com/audio.m4a",
            format = MediaFormat.M4A,
            itagItem = itagItem,
            averageBitrate = 128,
        )

    private fun itag(
        initStart: Int,
        initEnd: Int,
        indexStart: Int,
        indexEnd: Int,
    ): ItagInfo =
        ItagInfo.forItag(140)!!.apply {
            this.initStart = initStart
            this.initEnd = initEnd
            this.indexStart = indexStart
            this.indexEnd = indexEnd
        }

    @Test
    fun `segment ranges are the itag's inclusive init and index ranges`() {
        val ranges = audioStream(itag(0, 722, 723, 2962)).castSegmentRanges()

        assertThat(ranges).isEqualTo(CastSegmentRanges(init = 0L..722L, index = 723L..2962L))
        assertThat(ranges!!.init.length).isEqualTo(723)
    }

    @Test
    fun `streams without an itag or with empty ranges have no segment ranges`() {
        assertThat(audioStream(null).castSegmentRanges()).isNull()
        assertThat(audioStream(itag(-1, -1, 723, 2962)).castSegmentRanges()).isNull()
        assertThat(audioStream(itag(0, 722, 0, 0)).castSegmentRanges()).isNull()
    }
}
