package io.github.aedev.flow.player.quality

import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import io.github.aedev.flow.player.config.PlayerConfig
import io.github.aedev.flow.player.config.VideoSizeCap
import io.github.aedev.flow.player.config.adaptiveTrackSelectorDefaults
import io.github.aedev.flow.player.state.EnhancedPlayerState
import io.github.aedev.flow.player.state.QualityOption
import io.github.aedev.flow.player.stream.VideoCodecUtils
import kotlinx.coroutines.flow.MutableStateFlow
import org.schabi.newpipe.extractor.stream.VideoStream

@UnstableApi
class QualityManager(
    private val bandwidthMeter: DefaultBandwidthMeter?,
    private val trackSelector: DefaultTrackSelector?,
    private val videoSizeCap: VideoSizeCap,
    private val stateFlow: MutableStateFlow<EnhancedPlayerState>,
    // Receives the stream to switch to and the position playback must resume from.
    private val onQualitySwitch: (VideoStream, Long) -> Unit,
) {
    companion object {
        private const val TAG = "QualityManager"
        private const val MIN_QUALITY_SWITCH_INTERVAL_MS = 10_000L

        fun normalizeQualityHeight(rawHeight: Int): Int = VideoCodecUtils.normalizeQualityHeight(rawHeight)
    }

    // Quality mode tracking
    var isAdaptiveQualityEnabled = true
        private set
    var manualQualityHeight: Int? = null
        private set

    // Adaptive quality monitoring
    var lastBandwidthCheckTime = 0L
        private set
    var lastAdaptiveQualityHeight = 0
        private set
    var consecutiveBufferingCount = 0
        private set

    private var lastQualitySwitchTime = 0L

    var isDashSource = false

    /** Media3 is adapting over a ladder source, so Auto's own bandwidth checks stand down. */
    var isAdaptiveLadderActive = false

    // Available streams
    private var availableVideoStreams: List<VideoStream> = emptyList()
    private var currentVideoStream: VideoStream? = null

    var preferredCodecKey: String? = null

    // Track failed streams
    private val failedStreamUrls = mutableSetOf<String>()
    private val failedStreamVariants = mutableSetOf<String>()
    var streamErrorCount = 0
        private set

    /**
     * Update available video streams.
     */
    fun setAvailableStreams(streams: List<VideoStream>) {
        availableVideoStreams = streams.distinctBy { it.getContent() }.sortedByDescending { qualityHeight(it) }
    }

    /**
     * Set the current video stream.
     */
    fun setCurrentStream(stream: VideoStream?) {
        currentVideoStream = stream
        lastAdaptiveQualityHeight = stream?.let(::qualityHeight) ?: 0
    }

    /**
     * Get the current video stream.
     */
    fun getCurrentStream(): VideoStream? = currentVideoStream

    /**
     * Reset quality state for a new video.
     * Defaults to adaptive mode; call setManualMode() after if user has non-AUTO preference.
     */
    fun resetForNewVideo() {
        failedStreamUrls.clear()
        failedStreamVariants.clear()
        streamErrorCount = 0
        currentVideoStream = null
        isAdaptiveQualityEnabled = true
        manualQualityHeight = null
        consecutiveBufferingCount = 0
        lastQualitySwitchTime = 0L
        isAdaptiveLadderActive = false
        applyAdaptiveTrackSelectorDefaults()
    }

    /**
     * Set manual (fixed) quality mode. Called when user has a non-AUTO quality preference.
     * Disables adaptive quality switching.
     */
    fun setManualMode(height: Int) {
        isAdaptiveQualityEnabled = false
        manualQualityHeight = normalizeQualityHeight(height)
        Log.d(TAG, "Manual quality mode set: ${manualQualityHeight}p (adaptive disabled)")
    }

    /**
     * Mark a stream URL as failed.
     */
    fun markStreamFailed(url: String) {
        failedStreamUrls.add(url)
        availableVideoStreams.firstOrNull { streamKey(it) == url }?.let { stream ->
            failedStreamVariants.add(failureVariantKey(stream))
        }
        streamErrorCount++
        Log.w(TAG, "Stream marked as failed: $url - Error count: $streamErrorCount")
    }

    /**
     * Reset stream error count.
     */
    fun resetStreamErrors() {
        streamErrorCount = 0
    }

    /**
     * Check if a stream URL has failed.
     */
    fun hasStreamFailed(url: String): Boolean {
        if (failedStreamUrls.contains(url)) return true
        return availableVideoStreams
            .firstOrNull { streamKey(it) == url }
            ?.let { failedStreamVariants.contains(failureVariantKey(it)) }
            ?: false
    }

    /**
     * Get all working streams (streams that haven't failed).
     */
    fun getWorkingStreams(): List<VideoStream> =
        availableVideoStreams.filter {
            !failedStreamUrls.contains(streamKey(it)) &&
                !failedStreamVariants.contains(failureVariantKey(it))
        }

    private fun qualityHeight(stream: VideoStream): Int = normalizeQualityHeight(VideoCodecUtils.qualityHeightFromStream(stream))

    /**
     * Select smart initial quality based on bandwidth.
     */
    fun selectSmartInitialQuality(): VideoStream? {
        val estimatedBandwidth = bandwidthMeter?.bitrateEstimate ?: PlayerConfig.INITIAL_BANDWIDTH_ESTIMATE
        val smartStream = AutoQualityPolicy.initialPick(getWorkingStreams(), estimatedBandwidth, preferredCodecKey)
        Log.d(
            TAG,
            "Smart quality selection: bandwidth=${estimatedBandwidth / 1_000_000}Mbps, selected=${smartStream?.let(::qualityHeight)}p",
        )
        return smartStream
    }

    /** The qualities Auto hands to Media3 around [anchor], or none while a quality is pinned. */
    fun adaptiveLadder(anchor: VideoStream?): List<VideoStream> =
        if (isAdaptiveQualityEnabled) AdaptiveLadder.rungs(anchor, getWorkingStreams()) else emptyList()

    /**
     * Build quality options list for UI.
     */
    fun buildQualityOptions(): List<QualityOption> {
        return listOf(
            QualityOption(height = 0, label = "Auto", bitrate = 0L),
        ) +
            availableVideoStreams
                .groupBy { "${qualityHeight(it)}_${VideoCodecUtils.codecKeyFromStream(it)}" }
                .values
                .mapNotNull { streams ->
                    val bestStream = streams.maxByOrNull { it.bitrate } ?: return@mapNotNull null
                    val height = qualityHeight(bestStream)
                    val codecKey = VideoCodecUtils.codecKeyFromStream(bestStream)
                    val fps = VideoCodecUtils.frameRateFromStream(bestStream)
                    QualityOption(
                        height = height,
                        label = "${VideoCodecUtils.qualityLabelWithFrameRate(height, fps)} ${VideoCodecUtils.codecLabelFromKey(codecKey)}",
                        bitrate = bestStream.bitrate.toLong(),
                        codecKey = codecKey,
                        streamKey = streamKey(bestStream),
                        isHdr = VideoCodecUtils.isHdr(bestStream),
                    )
                }.sortedWith(
                    compareByDescending<QualityOption> { it.height }
                        .thenBy { VideoCodecUtils.playbackCodecRank(it.codecKey) }
                        .thenByDescending { it.bitrate },
                )
    }

    /**
     * Switch quality by height. Height 0 means auto (adaptive).
     */
    fun switchQualityByHeight(
        height: Int,
        currentPosition: Long,
    ): Boolean {
        // Height 0 means Auto (adaptive quality)
        if (height == 0) {
            enableAdaptiveQuality(currentPosition)
            return true
        }

        // Check if we're already at this quality
        if (manualQualityHeight == height) {
            Log.d(TAG, "Already at ${height}p, no change needed")
            return false
        }

        // Disable adaptive and set fixed quality
        isAdaptiveQualityEnabled = false
        manualQualityHeight = height

        Log.d(TAG, "Switching to FIXED quality: ${height}p")

        // Find the stream matching this height
        val targetStream =
            getWorkingStreams()
                .filter { qualityHeight(it) == height }
                .minWithOrNull(
                    compareBy<VideoStream> { VideoCodecUtils.codecRankWithPreference(it, preferredCodecKey) }
                        .thenByDescending { it.bitrate },
                )
        if (targetStream == null) {
            Log.w(TAG, "No stream found for ${height}p, available: ${availableVideoStreams.map { qualityHeight(it) }}")
            return false
        }

        val targetHeight = qualityHeight(targetStream)
        currentVideoStream = targetStream
        onQualitySwitch(targetStream, currentPosition)

        stateFlow.value =
            stateFlow.value.copy(
                currentQuality = targetHeight,
                effectiveQuality = targetHeight,
                currentQualityKey = streamKey(targetStream),
            )
        return true
    }

    fun switchQuality(
        option: QualityOption,
        currentPosition: Long,
    ): Boolean {
        option.streamKey?.let { return switchQualityByStreamKey(it, currentPosition) }
        return switchQualityByHeight(option.height, currentPosition)
    }

    fun switchQualityByStreamKey(
        streamKey: String,
        currentPosition: Long,
    ): Boolean {
        val targetStream = availableVideoStreams.firstOrNull { streamKey(it) == streamKey }
        if (targetStream == null) {
            Log.w(TAG, "No stream found for key=$streamKey")
            return false
        }

        val targetHeight = qualityHeight(targetStream)
        val targetCodec = VideoCodecUtils.codecKeyFromStream(targetStream)
        isAdaptiveQualityEnabled = false
        manualQualityHeight = targetHeight
        currentVideoStream = targetStream
        Log.d(TAG, "Switching to FIXED quality: ${targetHeight}p ${VideoCodecUtils.codecLabelFromKey(targetCodec)}")

        if (isDashSource) {
            switchDashQualitySeamlessly(targetStream.height, targetHeight)
        } else {
            onQualitySwitch(targetStream, currentPosition)
        }

        stateFlow.value =
            stateFlow.value.copy(
                currentQuality = targetHeight,
                effectiveQuality = targetHeight,
                currentQualityKey = streamKey,
            )
        return true
    }

    /**
     * Enable adaptive quality mode. A pinned quality is a single-stream source, so Auto reloads
     * whenever a ladder can take over from it, not only when the starting quality differs.
     */
    private fun enableAdaptiveQuality(currentPosition: Long) {
        val alreadyAdapting = isAdaptiveQualityEnabled && isAdaptiveLadderActive
        isAdaptiveQualityEnabled = true
        manualQualityHeight = null

        Log.d(TAG, "Enabling adaptive quality mode")
        applyAdaptiveTrackSelectorDefaults()
        if (alreadyAdapting) {
            stateFlow.value = stateFlow.value.copy(currentQuality = 0, currentQualityKey = null)
            return
        }

        val estimatedBandwidth = bandwidthMeter?.bitrateEstimate ?: PlayerConfig.INITIAL_BANDWIDTH_ESTIMATE
        val targetStream = AutoQualityPolicy.initialPick(getWorkingStreams(), estimatedBandwidth, preferredCodecKey)
        Log.d(TAG, "Auto quality: estimated bandwidth ${estimatedBandwidth / 1_000_000}Mbps -> ${targetStream?.let(::qualityHeight)}p")

        val heightChanges = targetStream != null && qualityHeight(targetStream) != currentVideoStream?.let(::qualityHeight)
        if (targetStream != null && (heightChanges || adaptiveLadder(targetStream).isNotEmpty())) {
            val selectedHeight = qualityHeight(targetStream)
            currentVideoStream = targetStream
            onQualitySwitch(targetStream, currentPosition)

            stateFlow.value =
                stateFlow.value.copy(
                    currentQuality = 0,
                    effectiveQuality = selectedHeight,
                    currentQualityKey = null,
                )
            lastAdaptiveQualityHeight = selectedHeight
        } else {
            stateFlow.value = stateFlow.value.copy(currentQuality = 0, currentQualityKey = null)
            lastAdaptiveQualityHeight = currentVideoStream?.let(::qualityHeight) ?: 0
        }
    }

    /**
     * Check if we can upgrade quality based on current bandwidth.
     * Called periodically when playback is smooth.
     */
    fun checkAdaptiveQualityUpgrade(currentPosition: Long) {
        if (!isAdaptiveQualityEnabled || isAdaptiveLadderActive) return
        val current = currentVideoStream ?: return
        val estimatedBandwidth = bandwidthMeter?.bitrateEstimate ?: return
        val next = AutoQualityPolicy.stepUp(current, getWorkingStreams(), estimatedBandwidth, preferredCodecKey) ?: return
        Log.d(
            TAG,
            "Adaptive UPGRADE: ${qualityHeight(current)}p -> ${qualityHeight(next)}p (bandwidth: ${estimatedBandwidth / 1_000_000}Mbps)",
        )
        performAdaptiveQualitySwitch(next, currentPosition)
    }

    /**
     * Check if we need to downgrade quality due to buffering or low bandwidth.
     */
    fun checkAdaptiveQualityDowngrade(
        forceCheck: Boolean,
        currentPosition: Long,
    ) {
        if (!isAdaptiveQualityEnabled || isAdaptiveLadderActive) return
        val current = currentVideoStream ?: return
        val streams = getWorkingStreams()
        if (forceCheck) {
            val lower = AutoQualityPolicy.stepDown(current, streams, preferredCodecKey) ?: return
            Log.d(TAG, "Adaptive DOWNGRADE (buffering): ${qualityHeight(current)}p -> ${qualityHeight(lower)}p")
            performAdaptiveQualitySwitch(lower, currentPosition)
            return
        }
        val estimatedBandwidth = bandwidthMeter?.bitrateEstimate ?: return
        val lower = AutoQualityPolicy.stepDownForBandwidth(current, streams, estimatedBandwidth, preferredCodecKey) ?: return
        Log.d(
            TAG,
            "Adaptive DOWNGRADE (low bandwidth): ${qualityHeight(current)}p -> ${qualityHeight(lower)}p " +
                "(bandwidth: ${estimatedBandwidth / 1_000_000}Mbps)",
        )
        performAdaptiveQualitySwitch(lower, currentPosition)
    }

    /**
     * Perform the actual quality switch for adaptive mode.
     * Includes time-based debounce to prevent rapid switching.
     */
    private fun performAdaptiveQualitySwitch(
        targetStream: VideoStream,
        currentPosition: Long,
    ) {
        val targetHeight = qualityHeight(targetStream)
        // Don't switch if we just switched recently (same height debounce)
        if (targetHeight == lastAdaptiveQualityHeight) {
            Log.d(TAG, "Adaptive: Skipping switch, already at ${targetHeight}p")
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastQualitySwitchTime < MIN_QUALITY_SWITCH_INTERVAL_MS) {
            Log.d(
                TAG,
                "Adaptive: Debouncing quality switch (${now - lastQualitySwitchTime}ms since last switch, min=${MIN_QUALITY_SWITCH_INTERVAL_MS}ms)",
            )
            return
        }

        currentVideoStream = targetStream
        lastAdaptiveQualityHeight = targetHeight
        lastQualitySwitchTime = now

        if (isDashSource) {
            switchDashQualitySeamlessly(targetStream.height, targetHeight)
        } else {
            onQualitySwitch(targetStream, currentPosition)
        }

        stateFlow.value =
            stateFlow.value.copy(
                currentQuality = 0,
                effectiveQuality = targetHeight,
                currentQualityKey = null,
            )
    }

    /**
     * Switch quality seamlessly via TrackSelector when using a DASH source.
     * This constrains the maximum video height, letting ExoPlayer
     * do a smooth in-buffer quality switch without interrupting playback.
     */
    private fun switchDashQualitySeamlessly(
        maxHeight: Int,
        qualityHeight: Int,
    ) {
        trackSelector?.let { selector ->
            val minHeight = (maxHeight - 1).coerceAtLeast(0)
            val params =
                selector
                    .buildUponParameters()
                    .setPreferredVideoMimeTypes(*PlayerConfig.PREFERRED_VIDEO_MIME_TYPES)
                    .setMinVideoSize(0, minHeight)
                    .setMaxVideoSize(Int.MAX_VALUE, maxHeight)
                    .setForceHighestSupportedBitrate(true)
                    .build()
            selector.setParameters(params)
            Log.d(TAG, "DASH seamless quality switch: constrained raw max height to ${maxHeight}px for ${qualityHeight}p")
        }
    }

    /**
     * Apply default track selector parameters for adaptive quality.
     */
    fun applyAdaptiveTrackSelectorDefaults() {
        trackSelector?.let { selector ->
            selector.setParameters(adaptiveTrackSelectorDefaults(selector.buildUponParameters(), videoSizeCap).build())
        }
    }

    private fun streamKey(stream: VideoStream): String = stream.getContent().takeIf { it.isNotBlank() } ?: stream.hashCode().toString()

    private fun failureVariantKey(stream: VideoStream): String {
        val height = qualityHeight(stream)
        val codec = VideoCodecUtils.codecKeyFromStream(stream)
        val itag =
            stream.itagItem?.id?.toString()
                ?: runCatching { stream.id }.getOrNull()?.takeIf { it.isNotBlank() }
        return listOfNotNull("${height}p", codec, itag?.let { "itag=$it" }).joinToString("|")
    }

    /**
     * Increment buffering count for adaptive quality tracking.
     */
    fun incrementBufferingCount() {
        if (isAdaptiveQualityEnabled) {
            consecutiveBufferingCount++
        }
    }

    /**
     * Reset buffering count (called when playback is smooth).
     */
    fun resetBufferingCount() {
        consecutiveBufferingCount = 0
    }

    /**
     * Check if buffering threshold is reached for quality downgrade.
     */
    fun hasReachedBufferingThreshold(): Boolean = consecutiveBufferingCount >= PlayerConfig.BUFFERING_COUNT_THRESHOLD

    /**
     * Update bandwidth check time.
     */
    fun updateBandwidthCheckTime() {
        lastBandwidthCheckTime = System.currentTimeMillis()
    }

    /**
     * Check if enough time has passed since last bandwidth check.
     */
    fun shouldCheckBandwidth(): Boolean {
        val currentTime = System.currentTimeMillis()
        return currentTime - lastBandwidthCheckTime >= PlayerConfig.BANDWIDTH_CHECK_INTERVAL_MS
    }

    /**
     * Attempt quality downgrade when stream is corrupted.
     * Returns the new stream or null if no alternatives available.
     */
    fun attemptQualityDowngrade(): VideoStream? {
        if (!isAdaptiveQualityEnabled) {
            Log.w(TAG, "Manual quality selected - skipping automatic downgrade")
            return currentVideoStream
        }

        val workingStreams = getWorkingStreams()

        if (workingStreams.isEmpty()) {
            Log.e(TAG, "No working streams available - all streams failed")
            return null
        }

        if (failedStreamUrls.size >= availableVideoStreams.size) {
            Log.e(TAG, "All available streams exhausted - stopping playback")
            return null
        }

        // Prefer MP4 over WebM for better compatibility
        val lowerQualityStream =
            workingStreams
                .sortedWith(
                    compareBy(
                        { it.format?.mimeType?.contains("webm", ignoreCase = true) == true },
                        { qualityHeight(it) },
                    ),
                ).firstOrNull()

        if (lowerQualityStream != null) {
            val lowerHeight = qualityHeight(lowerQualityStream)
            Log.w(
                TAG,
                "Downgrading quality to ${lowerHeight}p (${lowerQualityStream.format?.mimeType}) - Failed: ${failedStreamUrls.size}/${availableVideoStreams.size}",
            )
            currentVideoStream = lowerQualityStream
            streamErrorCount = 0

            stateFlow.value =
                stateFlow.value.copy(
                    currentQuality = lowerHeight,
                    currentQualityKey = streamKey(lowerQualityStream),
                    isBuffering = true,
                )

            return lowerQualityStream
        }

        return null
    }

    fun fallbackToAlternateCodec(currentPosition: Long): Boolean {
        val current = currentVideoStream ?: return false
        val failingCodec = VideoCodecUtils.codecKeyFromStream(current)
        if (!failingCodec.equals("av1", ignoreCase = true)) return false

        val currentHeight = qualityHeight(current)
        markStreamFailed(current.getContent())

        val candidate =
            getWorkingStreams()
                .filter { !VideoCodecUtils.codecKeyFromStream(it).equals("av1", ignoreCase = true) }
                .minWithOrNull(
                    compareBy<VideoStream> { kotlin.math.abs(qualityHeight(it) - currentHeight) }
                        .thenBy { VideoCodecUtils.playbackCodecRank(it) }
                        .thenByDescending { it.bitrate },
                )
        if (candidate == null) {
            Log.w(TAG, "Codec fallback: no non-AV1 stream available for ${currentHeight}p")
            return false
        }

        val targetHeight = qualityHeight(candidate)
        val targetCodec = VideoCodecUtils.codecKeyFromStream(candidate)
        currentVideoStream = candidate
        streamErrorCount = 0
        lastAdaptiveQualityHeight = targetHeight
        if (!isAdaptiveQualityEnabled) manualQualityHeight = targetHeight

        Log.w(
            TAG,
            "Codec fallback: ${VideoCodecUtils.codecLabelFromKey(
                failingCodec,
            )} ${currentHeight}p gated by CDN (403) → ${VideoCodecUtils.codecLabelFromKey(targetCodec)} ${targetHeight}p",
        )

        onQualitySwitch(candidate, currentPosition)

        stateFlow.value =
            stateFlow.value.copy(
                currentQuality = if (isAdaptiveQualityEnabled) 0 else targetHeight,
                effectiveQuality = targetHeight,
                currentQualityKey = if (isAdaptiveQualityEnabled) null else streamKey(candidate),
                isBuffering = true,
                error = null,
            )
        return true
    }
}
