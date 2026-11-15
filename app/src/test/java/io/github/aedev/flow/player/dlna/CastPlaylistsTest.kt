package io.github.aedev.flow.player.dlna

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ChunkIndex
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CastPlaylistsTest {
    @Test
    fun `video streams get an mp4 segment extension`() {
        assertThat(CastPlaylists.segmentFileExtension("video/mp4")).isEqualTo("mp4")
    }

    @Test
    fun `audio streams get an m4a segment extension`() {
        assertThat(CastPlaylists.segmentFileExtension("audio/mp4")).isEqualTo("m4a")
    }

    @Test
    fun `webm streams keep their own container extension`() {
        assertThat(CastPlaylists.segmentFileExtension("video/webm")).isEqualTo("webm")
        assertThat(CastPlaylists.segmentFileExtension("audio/webm")).isEqualTo("weba")
    }

    @Test
    fun `unknown content types fall back to mp4`() {
        assertThat(CastPlaylists.segmentFileExtension("")).isEqualTo("mp4")
        assertThat(CastPlaylists.segmentFileExtension("application/octet-stream")).isEqualTo("mp4")
    }

    /**
     * DlnaCastManager only builds HLS variants from mp4/avc video and mp4/m4a/aac audio, so these
     * are the only content types whose extension an ffmpeg-based renderer ever validates.
     */
    @Test
    fun `content types referenced from generated playlists map onto ffmpeg's allow-list`() {
        // libavformat/hls.c `allowed_segment_extensions` default; ffmpeg 7.1+ drops anything else.
        val allowed =
            setOf(
                "3gp",
                "aac",
                "avi",
                "ac3",
                "eac3",
                "flac",
                "mkv",
                "m3u8",
                "m4a",
                "m4s",
                "m4v",
                "mpg",
                "mov",
                "mp2",
                "mp3",
                "mp4",
                "mpeg",
                "mpegts",
                "ogg",
                "ogv",
                "oga",
                "ts",
                "vob",
                "wav",
            )
        val hlsContentTypes = listOf("video/mp4", "audio/mp4")
        assertThat(allowed).containsAtLeastElementsIn(hlsContentTypes.map(CastPlaylists::segmentFileExtension))
    }

    @OptIn(UnstableApi::class)
    @Test
    fun `segmented playlists map the init segment and each index entry onto byte ranges`() {
        val index =
            ChunkIndex(
                intArrayOf(1_000, 2_000),
                longArrayOf(4_434, 5_434),
                longArrayOf(6_000_000, 5_500_000),
                longArrayOf(0, 6_000_000),
            )

        val playlist = CastPlaylists.segmentedMediaPlaylist("http://phone/s/v.mp4", 0L..741L, index)

        assertThat(playlist).isEqualTo(
            """
            #EXTM3U
            #EXT-X-VERSION:6
            #EXT-X-TARGETDURATION:6
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXT-X-MAP:URI="http://phone/s/v.mp4",BYTERANGE="742@0"
            #EXTINF:6.000,
            #EXT-X-BYTERANGE:1000@4434
            http://phone/s/v.mp4
            #EXTINF:5.500,
            #EXT-X-BYTERANGE:2000@5434
            http://phone/s/v.mp4
            #EXT-X-ENDLIST

            """.trimIndent(),
        )
    }
}
