package io.github.aedev.flow.data.video.downloader.transfer

import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadTraceTest {
    private var nanos = 0L
    private val trace = DownloadTrace("vid") { nanos }

    @Test
    fun `throughput and first-byte times come from the counted requests`() {
        trace.onResponse(sentAtNanos = nanos.also { nanos += 100_000_000 })
        trace.onResponse(sentAtNanos = nanos.also { nanos += 300_000_000 })
        trace.onBytes(1_000_000)
        nanos = 2_000_000_000

        val line = trace.line("completed", "threads=3")

        assertTrue(line, "requests=2 " in line)
        assertTrue(line, "ttfbAvgMs=200 " in line)
        assertTrue(line, "ttfbMaxMs=300 " in line)
        assertTrue(line, "mbps=4.00 " in line)
        assertTrue(line, line.endsWith(" threads=3"))
    }

    @Test
    fun `expired and gated refusals are counted apart`() {
        trace.onDenied(expired = true)
        trace.onDenied(expired = true)
        trace.onDenied(expired = false)

        val line = trace.line("failed:REFUSED", "")

        assertTrue(line, "expired403=2 gated403=1" in line)
        assertTrue(line, "outcome=failed:REFUSED" in line)
    }
}
