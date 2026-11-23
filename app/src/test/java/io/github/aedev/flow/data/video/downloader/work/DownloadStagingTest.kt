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
    fun `clearing a download removes its SABR files too`() {
        val staging = DownloadStaging(folder.root, "vid")
        listOf(staging.videoPart, staging.sabrAudioPart).forEach { it.writeText("x") }

        staging.clear()

        assertFalse(staging.videoPart.exists() || staging.sabrAudioPart.exists())
    }
}
