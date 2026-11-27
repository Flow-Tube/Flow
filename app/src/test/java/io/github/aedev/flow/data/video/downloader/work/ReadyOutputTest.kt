package io.github.aedev.flow.data.video.downloader.work

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class ReadyOutputTest {
    @Test
    fun `the saved gain survives a restart between checking and placing the file`() {
        val file = File.createTempFile("ready", ".json").apply { deleteOnExit() }

        ReadyOutput.write(file, ReadyOutput(extension = "m4a", quality = "128 kbps", loudnessGainDb = -3.5f))

        assertThat(ReadyOutput.read(file)?.loudnessGainDb).isEqualTo(-3.5f)
    }

    @Test
    fun `a ready file written before the gain was saved still reads`() {
        val file = File.createTempFile("ready", ".json").apply { deleteOnExit() }
        file.writeText("""{"extension":"mp4","quality":"VP9 1080p"}""")

        val ready = ReadyOutput.read(file)

        assertThat(ready?.extension).isEqualTo("mp4")
        assertThat(ready?.loudnessGainDb).isNull()
    }
}
