package io.github.aedev.flow.player.live

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.util.concurrent.atomic.AtomicLong

/** Bytes and requests live playback fetched, split into manifests and media. */
data class LiveTraffic(
    val manifestRequests: Long = 0,
    val manifestBytes: Long = 0,
    val mediaRequests: Long = 0,
    val mediaBytes: Long = 0,
) {
    operator fun minus(other: LiveTraffic) =
        LiveTraffic(
            manifestRequests - other.manifestRequests,
            manifestBytes - other.manifestBytes,
            mediaRequests - other.mediaRequests,
            mediaBytes - other.mediaBytes,
        )
}

/** Counts the network transfers of the live data sources it is attached to. */
@UnstableApi
object LiveTrafficMeter : TransferListener {
    private val manifestRequests = AtomicLong()
    private val manifestBytes = AtomicLong()
    private val mediaRequests = AtomicLong()
    private val mediaBytes = AtomicLong()

    fun snapshot() = LiveTraffic(manifestRequests.get(), manifestBytes.get(), mediaRequests.get(), mediaBytes.get())

    override fun onTransferInitializing(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
    ) = Unit

    override fun onTransferStart(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
    ) {
        if (!isNetwork) return
        val counter = if (isManifest(dataSpec.uri)) manifestRequests else mediaRequests
        counter.incrementAndGet()
    }

    override fun onBytesTransferred(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
        bytesTransferred: Int,
    ) {
        if (!isNetwork) return
        val counter = if (isManifest(dataSpec.uri)) manifestBytes else mediaBytes
        counter.addAndGet(bytesTransferred.toLong())
    }

    override fun onTransferEnd(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
    ) = Unit

    private fun isManifest(uri: Uri): Boolean {
        val path = uri.path ?: return false
        return "/hls_playlist/" in path || "/hls_variant/" in path || "/dash/" in path || path.endsWith(".m3u8") || path.endsWith(".mpd")
    }
}
