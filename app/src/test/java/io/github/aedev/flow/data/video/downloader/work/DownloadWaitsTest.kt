package io.github.aedev.flow.data.video.downloader.work

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DownloadWaitsTest {
    private var now = 1_000_000L
    private val waits = DownloadWaits { now }

    @Test
    fun `each failure in a row doubles the wait up to an hour`() {
        val delays = (1..10).map { attempt -> DownloadWaits.delayMs(attempt) / 1_000 }

        assertThat(delays).containsExactly(30L, 60L, 120L, 240L, 480L, 960L, 1920L, 3600L, 3600L, 3600L).inOrder()
    }

    @Test
    fun `a held download is skipped until its wait runs out`() {
        val wait = waits.hold("a", DownloadWaitReason.NETWORK)!!

        assertThat(waits.held()).containsExactly("a")
        assertThat(waits.nextRetryInMs()).isEqualTo(30_000L)
        now = wait.retryAtMs
        assertThat(waits.held()).isEmpty()
        assertThat(waits.nextRetryInMs()).isNull()
    }

    @Test
    fun `a download still failing a day after its first wait fails for good`() {
        waits.hold("a", DownloadWaitReason.NETWORK)
        now += DownloadWaits.GIVE_UP_AFTER_MS - 1
        assertThat(waits.hold("a", DownloadWaitReason.NETWORK)).isNotNull()
        now += 1
        assertThat(waits.hold("a", DownloadWaitReason.NETWORK)).isNull()
        assertThat(waits.held()).isEmpty()
    }

    @Test
    fun `clearing a wait starts the backoff over`() {
        waits.hold("a", DownloadWaitReason.NETWORK)
        waits.hold("a", DownloadWaitReason.NETWORK)
        waits.clear("a")

        assertThat(waits.hold("a", DownloadWaitReason.NETWORK)!!.attempt).isEqualTo(1)
    }

    @Test
    fun `storage is checked again at least every 15 minutes`() {
        repeat(9) { waits.hold("a", DownloadWaitReason.STORAGE) }

        assertThat(waits.nextRetryInMs()).isEqualTo(DownloadWaits.STORAGE_MAX_DELAY_MS)
    }

    @Test
    fun `freed space lets storage waits go but not network waits`() {
        waits.hold("a", DownloadWaitReason.STORAGE)
        waits.hold("b", DownloadWaitReason.NETWORK)

        waits.releaseStorage()

        assertThat(waits.held()).containsExactly("b")
        assertThat(waits.released.value).isEqualTo(1)
    }

    @Test
    fun `only failures that pass on their own wait`() {
        assertThat(waitFor(FetchFailure.NETWORK)).isEqualTo(DownloadWaitReason.NETWORK)
        assertThat(waitFor(FetchFailure.LENGTH_UNKNOWN)).isEqualTo(DownloadWaitReason.NETWORK)
        assertThat(waitFor(FetchFailure.DISK_FULL)).isEqualTo(DownloadWaitReason.STORAGE)
        assertThat(waitFor(FetchFailure.REFUSED)).isNull()
        assertThat(waitFor(FetchFailure.UNAVAILABLE)).isNull()
    }
}
