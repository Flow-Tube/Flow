package io.github.aedev.flow.player.stats

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.R
import org.junit.Test

class PlayerStatsFormatterTest {
    private val full =
        PlayerStatsSnapshot(
            device = "motorola edge 40 neo Android 15",
            videoId = "Wn5_cnz04KA",
            kind = "vod",
            client = "VISIONOS",
            video = PlayerStatsSnapshot.VideoStats("248", "vp9", 1920, 1080, 30f),
            decoder = "c2.qti.vp9.decoder",
            audio = PlayerStatsSnapshot.AudioStats("251", "opus", 160_000, 48_000),
            volume = 0.46f,
            gainDb = -3.2f,
            bandwidthBitsPerSecond = 18_400_000L,
            bufferedMs = 85_700L,
            viewportWidth = 1080,
            viewportHeight = 608,
            droppedFrames = 18,
            renderedFrames = 4469,
        )

    private fun value(
        snapshot: PlayerStatsSnapshot,
        label: Int,
    ) = PlayerStatsFormatter.lines(snapshot).single { it.label == label }.value

    @Test
    fun `a playing video reads as the panel shows it`() {
        assertThat(value(full, R.string.stats_video_id)).isEqualTo("Wn5_cnz04KA [vod] VISIONOS")
        assertThat(value(full, R.string.stats_video)).isEqualTo("248 vp9 1920x1080@30")
        assertThat(value(full, R.string.stats_audio)).isEqualTo("251 opus 160 kbps 48.0 kHz")
        assertThat(value(full, R.string.stats_volume)).isEqualTo("46% -3.2 dB")
        assertThat(value(full, R.string.stats_bandwidth)).isEqualTo("18.4 Mbps")
        assertThat(value(full, R.string.stats_buffered)).isEqualTo("85.7 s")
        assertThat(value(full, R.string.stats_viewport)).isEqualTo("1080x608")
        assertThat(value(full, R.string.stats_dropped_frames)).isEqualTo("18 / 4469")
    }

    @Test
    fun `a viewport the player could not measure reads as unknown`() {
        val unmeasured = full.copy(viewportWidth = -1, viewportHeight = -1)

        assertThat(value(unmeasured, R.string.stats_viewport)).isEqualTo("-")
    }

    @Test
    fun `a slow connection reads in kbps`() {
        assertThat(value(full.copy(bandwidthBitsPerSecond = 640_000L), R.string.stats_bandwidth)).isEqualTo("640 kbps")
    }

    @Test
    fun `a live stream with no client and no gain leaves them out`() {
        val live = full.copy(kind = "live", client = null, gainDb = null)

        assertThat(value(live, R.string.stats_video_id)).isEqualTo("Wn5_cnz04KA [live]")
        assertThat(value(live, R.string.stats_volume)).isEqualTo("46%")
    }

    @Test
    fun `an audio only moment has placeholders rather than gaps`() {
        val audioOnly = full.copy(video = null, decoder = null)

        assertThat(value(audioOnly, R.string.stats_video)).isEqualTo("-")
        assertThat(value(audioOnly, R.string.stats_decoder)).isEqualTo("-")
    }

    @Test
    fun `a stream with no reported rates leaves them out`() {
        val bare =
            full.copy(
                video = PlayerStatsSnapshot.VideoStats(null, "avc1", 1280, 720, -1f),
                audio = PlayerStatsSnapshot.AudioStats(null, "mp4a", -1, -1),
            )

        assertThat(value(bare, R.string.stats_video)).isEqualTo("avc1 1280x720")
        assertThat(value(bare, R.string.stats_audio)).isEqualTo("mp4a")
    }
}
