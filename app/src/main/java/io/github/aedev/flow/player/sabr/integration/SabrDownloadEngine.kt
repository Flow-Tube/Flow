package io.github.aedev.flow.player.sabr.integration

import android.util.Log
import io.github.aedev.flow.player.sabr.core.SabrEvent
import io.github.aedev.flow.player.sabr.core.SabrSessionState
import io.github.aedev.flow.player.sabr.core.SabrStreamController
import io.github.aedev.flow.player.sabr.network.SabrDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class SabrDownloadEngine {
    companion object {
        private const val TAG = "SabrDownloadEngine"
        private const val MAX_CONSECUTIVE_ERRORS = 8
        private const val USER_AGENT = "com.google.android.youtube/21.03.38 (Linux; U; Android 14) gzip"
    }

    @Volatile
    var isCancelled = false
        private set

    val downloadedVideoBytes = AtomicLong(0)
    val downloadedAudioBytes = AtomicLong(0)
    private val maxBufferedTimeMs = AtomicLong(0)

    fun cancel() {
        isCancelled = true
    }

    suspend fun download(
        streamingUrl: String,
        videoId: String,
        audioItag: Int,
        audioLmt: Long,
        videoItag: Int,
        videoLmt: Long,
        audioXtags: String = "",
        videoXtags: String = "",
        audioTrackId: String = "",
        poToken: String,
        visitorId: String,
        ustreamerConfig: ByteArray,
        durationMs: Long,
        videoOutputPath: String,
        audioOutputPath: String,
        audioOnly: Boolean = false,
        onProgress: (downloadedBytes: Long, estimatedTotalBytes: Long) -> Unit = { _, _ -> },
    ): Boolean =
        withContext(Dispatchers.IO) {
            isCancelled = false
            downloadedVideoBytes.set(0)
            downloadedAudioBytes.set(0)
            maxBufferedTimeMs.set(0)

            val sessionState =
                SabrSessionState().apply {
                    this.streamingUrl = streamingUrl
                    this.videoId = videoId
                    this.selectedAudioItag = audioItag
                    this.selectedAudioLmt = audioLmt
                    this.selectedVideoItag = if (audioOnly) 0 else videoItag
                    this.selectedVideoLmt = if (audioOnly) 0 else videoLmt
                    this.selectedAudioXtags = audioXtags
                    this.audioTrackId = audioTrackId
                    this.selectedVideoXtags = if (audioOnly) "" else videoXtags
                    this.poToken = poToken
                    this.visitorId = visitorId
                    this.ustreamerConfig = ustreamerConfig
                    this.durationMs = durationMs
                    this.playheadPositionMs = 0
                    if (audioOnly) this.enabledTrackTypes = 1
                }

            val dataSource = SabrDataSource(USER_AGENT)
            val controller = SabrStreamController(dataSource, sessionState)

            var videoStream: FileOutputStream? = null
            var audioStream: FileOutputStream? = null
            val endOfTrackReached = AtomicBoolean(false)
            var consecutiveErrors = 0
            var success = false

            try {
                File(videoOutputPath).parentFile?.mkdirs()
                File(audioOutputPath).parentFile?.mkdirs()

                if (!audioOnly) {
                    videoStream = FileOutputStream(videoOutputPath)
                }
                audioStream = FileOutputStream(audioOutputPath)

                Log.d(
                    TAG,
                    "Starting SABR download: video=$videoId, audioItag=$audioItag, " +
                        "videoItag=$videoItag, duration=${durationMs}ms, audioOnly=$audioOnly",
                )

                coroutineScope {
                    val eventJob: Job =
                        launch {
                            controller.events.collect { event ->
                                if (isCancelled) return@collect
                                when (event) {
                                    is SabrEvent.FormatInitialized -> {
                                        val initData = event.metadata.initData
                                        if (initData.isNotEmpty()) {
                                            if (event.metadata.isAudio) {
                                                audioStream?.write(initData)
                                                downloadedAudioBytes.addAndGet(initData.size.toLong())
                                                Log.d(
                                                    TAG,
                                                    "Audio init: ${event.metadata.mimeType} ${event.metadata.codecs}, " +
                                                        "${initData.size}B",
                                                )
                                            } else if (event.metadata.isVideo && !audioOnly) {
                                                videoStream?.write(initData)
                                                downloadedVideoBytes.addAndGet(initData.size.toLong())
                                                Log.d(
                                                    TAG,
                                                    "Video init: ${event.metadata.mimeType} ${event.metadata.codecs}, " +
                                                        "${event.metadata.width}x${event.metadata.height}, ${initData.size}B",
                                                )
                                            }
                                        }
                                    }

                                    is SabrEvent.SegmentReady -> {
                                        consecutiveErrors = 0
                                        val segment = event.segment
                                        if (segment.isAudio) {
                                            audioStream?.write(segment.data)
                                            downloadedAudioBytes.addAndGet(segment.data.size.toLong())
                                        } else if (!audioOnly) {
                                            videoStream?.write(segment.data)
                                            downloadedVideoBytes.addAndGet(segment.data.size.toLong())
                                        }

                                        val segEndMs = segment.timeRangeStartMs + segment.durationMs
                                        val prev = maxBufferedTimeMs.get()
                                        if (segEndMs > prev) {
                                            maxBufferedTimeMs.set(segEndMs)
                                        }

                                        val totalDownloaded = downloadedVideoBytes.get() + downloadedAudioBytes.get()
                                        val estimatedTotal =
                                            if (durationMs > 0 && maxBufferedTimeMs.get() > 0) {
                                                (totalDownloaded.toDouble() / maxBufferedTimeMs.get() * durationMs).toLong()
                                            } else {
                                                0L
                                            }
                                        onProgress(totalDownloaded, estimatedTotal)
                                    }

                                    is SabrEvent.EndOfTrack -> {
                                        Log.d(TAG, "End of track — download complete")
                                        endOfTrackReached.set(true)
                                    }

                                    is SabrEvent.Error -> {
                                        consecutiveErrors++
                                        Log.e(
                                            TAG,
                                            "SABR download error: code=${event.code}, msg=${event.message}, " +
                                                "recoverable=${event.recoverable}, consecutive=$consecutiveErrors",
                                        )
                                        if (!event.recoverable || consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                                            isCancelled = true
                                        }
                                    }

                                    is SabrEvent.Redirect -> {
                                        Log.d(TAG, "Redirected during download")
                                    }

                                    is SabrEvent.BackoffRequired -> {
                                        Log.d(TAG, "Backoff during download: ${event.delayMs}ms")
                                    }

                                    is SabrEvent.ReloadRequired -> {
                                        Log.w(TAG, "Reload required during download: ${event.reason}")
                                        isCancelled = true
                                    }

                                    is SabrEvent.SeekDirective -> {
                                        Log.d(TAG, "Seek directive ignored during download: ${event.targetMs}ms")
                                    }

                                    is SabrEvent.AttestationNeeded -> {
                                        Log.w(TAG, "Attestation needed during download (required=${event.required})")
                                    }
                                }
                            }
                        }

                    launch(Dispatchers.IO) {
                        try {
                            controller.startSession()

                            while (isActive && !isCancelled && !endOfTrackReached.get() &&
                                consecutiveErrors < MAX_CONSECUTIVE_ERRORS
                            ) {
                                delay(50)
                                if (isCancelled || endOfTrackReached.get()) break
                                try {
                                    sessionState.playheadPositionMs = maxBufferedTimeMs.get()
                                    controller.requestNextSegments()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    consecutiveErrors++
                                    Log.e(TAG, "Follow-up request failed ($consecutiveErrors/$MAX_CONSECUTIVE_ERRORS)", e)
                                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) break
                                    delay(1000L * consecutiveErrors)
                                }
                            }
                        } finally {
                            eventJob.cancel()
                        }
                    }

                    eventJob.join()
                }

                success = endOfTrackReached.get() && !isCancelled
                if (success) {
                    val totalBytes = downloadedVideoBytes.get() + downloadedAudioBytes.get()
                    onProgress(totalBytes, totalBytes)
                    Log.d(
                        TAG,
                        "SABR download complete: video=${downloadedVideoBytes.get()}B, " +
                            "audio=${downloadedAudioBytes.get()}B, total=$totalBytes",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "SABR download failed", e)
                success = false
            } finally {
                controller.release()
                try {
                    videoStream?.close()
                } catch (_: Exception) {
                }
                try {
                    audioStream?.close()
                } catch (_: Exception) {
                }

                if (!success) {
                    File(videoOutputPath).takeIf { it.exists() && it.length() == 0L }?.delete()
                    File(audioOutputPath).takeIf { it.exists() && it.length() == 0L }?.delete()
                }
            }

            success
        }
}
