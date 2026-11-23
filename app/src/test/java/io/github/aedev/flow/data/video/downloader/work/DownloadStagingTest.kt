package io.github.aedev.flow.data.video.downloader.work

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadStagingTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `a SABR fallback starting over leaves the direct blocks alone`() {
        val staging = DownloadStaging(folder.root, "vid")
        listOf(staging.videoPart, staging.audioPart, staging.state, staging.sabrVideoPart, staging.sabrAudioPart)
            .forEach { it.writeText("x") }

        staging.clearSabrParts()

        assertTrue(staging.videoPart.exists() && staging.audioPart.exists() && staging.state.exists())
        assertFalse(staging.sabrVideoPart.exists() || staging.sabrAudioPart.exists())
    }

    @Test
    fun `a checked output waiting to be copied outlives its parts`() {
        val staging = DownloadStaging(folder.root, "vid")
        val output = staging.output("mp4")
        listOf(staging.videoPart, staging.audioPart, staging.state, staging.sabrAudioPart, output).forEach { it.writeText("x") }
        ReadyOutput.write(
            staging.ready,
            ReadyOutput(extension = "mp4", quality = "VP9 1080p", savedQuality = "VP9 1080p", wantedQuality = "AV1 1080p"),
        )

        staging.clearInputs()

        assertFalse(staging.videoPart.exists() || staging.audioPart.exists() || staging.state.exists() || staging.sabrAudioPart.exists())
        assertTrue(output.exists())
        assertTrue(ReadyOutput.read(staging.ready)?.wantedQuality == "AV1 1080p")
        staging.clear()
        assertFalse(output.exists() || staging.ready.exists())
    }

    @Test
    fun `clearing a download removes its SABR files too`() {
        val staging = DownloadStaging(folder.root, "vid")
        listOf(staging.videoPart, staging.sabrAudioPart).forEach { it.writeText("x") }

        staging.clear()

        assertFalse(staging.videoPart.exists() || staging.sabrAudioPart.exists())
    }
}
