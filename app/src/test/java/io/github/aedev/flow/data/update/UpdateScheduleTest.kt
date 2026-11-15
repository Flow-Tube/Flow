package io.github.aedev.flow.data.update

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class UpdateScheduleTest {
    private val now = 1_000_000_000L

    @Test
    fun `a check inside the cooldown is not due, one after it is`() {
        assertThat(UpdateSchedule.isDue(now - UpdateSchedule.COOLDOWN_MS + 1, now)).isFalse()
        assertThat(UpdateSchedule.isDue(now - UpdateSchedule.COOLDOWN_MS, now)).isTrue()
    }

    @Test
    fun `a clock moved backwards makes the check due`() {
        assertThat(UpdateSchedule.isDue(now + 1, now)).isTrue()
    }

    @Test
    fun `a skipped or already shown version is not announced again`() {
        assertThat(UpdateSchedule.mayAnnounce("2.3.0", skippedVersion = "2.3.0", announcedVersion = null)).isFalse()
        assertThat(UpdateSchedule.mayAnnounce("2.3.0", skippedVersion = null, announcedVersion = "2.3.0")).isFalse()
        assertThat(UpdateSchedule.mayAnnounce("2.3.1", skippedVersion = "2.3.0", announcedVersion = "2.3.0")).isTrue()
    }

    @Test
    fun `the launch page opens on every launch while the update is not downloaded`() {
        repeat(3) { assertThat(UpdateSchedule.shouldOpenOnLaunch("nightly.2481", null, UpdateDownload.Idle)).isTrue() }
    }

    @Test
    fun `the launch page stays closed while the update downloads or is checked`() {
        assertThat(UpdateSchedule.shouldOpenOnLaunch("2.3.0", null, UpdateDownload.Running(0.4f))).isFalse()
        assertThat(UpdateSchedule.shouldOpenOnLaunch("2.3.0", null, UpdateDownload.Running(null))).isFalse()
        assertThat(UpdateSchedule.shouldOpenOnLaunch("2.3.0", null, UpdateDownload.Verifying)).isFalse()
    }

    @Test
    fun `the launch page stays closed once the download is ready`() {
        assertThat(UpdateSchedule.shouldOpenOnLaunch("2.3.0", null, UpdateDownload.Ready)).isFalse()
    }

    @Test
    fun `the launch page opens again after a failed download`() {
        assertThat(UpdateSchedule.shouldOpenOnLaunch("2.3.0", null, UpdateDownload.Failed(UpdateFailure.NETWORK))).isTrue()
    }

    @Test
    fun `a skipped version never opens the launch page`() {
        assertThat(UpdateSchedule.shouldOpenOnLaunch("2.3.0", "2.3.0", UpdateDownload.Idle)).isFalse()
        assertThat(UpdateSchedule.shouldOpenOnLaunch("2.3.1", "2.3.0", UpdateDownload.Idle)).isTrue()
    }

    @Test
    fun `a launch reuses the release this process already fetched`() =
        runBlocking {
            var fetches = 0
            val release =
                UpdateSchedule.releaseForLaunch(alreadyFetched = true, known = "nightly.2481") {
                    fetches++
                    "other"
                }
            assertThat(release).isEqualTo("nightly.2481")
            assertThat(fetches).isEqualTo(0)
        }

    @Test
    fun `a launch reuses a fetch that found no newer release`() =
        runBlocking {
            var fetches = 0
            val release =
                UpdateSchedule.releaseForLaunch<String>(alreadyFetched = true, known = null) {
                    fetches++
                    "other"
                }
            assertThat(release).isNull()
            assertThat(fetches).isEqualTo(0)
        }

    @Test
    fun `the first launch of a process fetches once`() =
        runBlocking {
            var fetches = 0
            val release =
                UpdateSchedule.releaseForLaunch<String>(alreadyFetched = false, known = null) {
                    fetches++
                    "nightly.2481"
                }
            assertThat(release).isEqualTo("nightly.2481")
            assertThat(fetches).isEqualTo(1)
        }
}
