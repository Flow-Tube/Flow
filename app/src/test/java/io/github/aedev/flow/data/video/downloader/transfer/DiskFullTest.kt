package io.github.aedev.flow.data.video.downloader.transfer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class DiskFullTest {
    @Test
    fun `a write that ran out of space is disk full`() {
        assertTrue(DiskFull.isCause(IOException("write failed: ENOSPC (No space left on device)")))
    }

    @Test
    fun `a wrapped out-of-space error is still disk full`() {
        assertTrue(DiskFull.isCause(IllegalStateException("muxer", IOException("No space left on device"))))
    }

    @Test
    fun `a reset connection is not disk full`() {
        assertFalse(DiskFull.isCause(IOException("Connection reset", IOException("ECONNRESET"))))
    }
}
