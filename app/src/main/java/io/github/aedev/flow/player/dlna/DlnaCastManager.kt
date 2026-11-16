package io.github.aedev.flow.player.dlna

import android.content.Context
import android.util.Log
import io.github.aedev.flow.player.stream.VideoCodecUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.VideoStream
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * DLNA/UPnP casting engine — zero Google Play Services dependencies.
 *
 * ## Protocol overview
 *  1. **SSDP discovery** — sends an M-SEARCH UDP multicast to 239.255.255.250:1900 and
 *     listens for UPnP AVTransport services.
 *  2. **Device description** — fetches the device description XML over HTTP and locates
 *     the control URL for the `urn:schemas-upnp-org:service:AVTransport:1` service.
 *  3. **SOAP control** — sends SetAVTransportURI, Play, Pause, and Stop actions to the
 *     control URL; no external library required.
 *
 * All network calls are made on [Dispatchers.IO]; state is exposed as [StateFlow]s so
 * Compose can collect them directly.
 */
object DlnaCastManager {
    private const val TAG = "DlnaCastManager"
    private const val STOP_TIMEOUT_MS = 5_000L
    private const val SEGMENTED_START_TIMEOUT_MS = 10_000L
    private val SOAP_TYPE = "text/xml; charset=\"utf-8\"".toMediaType()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val http =
        OkHttpClient
            .Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()

    private val _devices = MutableStateFlow<List<DlnaDevice>>(emptyList())
    val devices: StateFlow<List<DlnaDevice>> = _devices.asStateFlow()

    private val _currentDevice = MutableStateFlow<DlnaDevice?>(null)
    val currentDevice: StateFlow<DlnaDevice?> = _currentDevice.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    val isCasting: Boolean get() = _currentDevice.value != null

    private var discoveryJob: Job? = null
    private val discovery = SsdpDiscovery(http, scope)

    private val proxy = StreamProxyServer.getInstance()

    private val _castPosition = MutableStateFlow(0L)
    val castPosition: StateFlow<Long> = _castPosition.asStateFlow()

    private val _castDuration = MutableStateFlow(0L)
    val castDuration: StateFlow<Long> = _castDuration.asStateFlow()

    private var positionPollingJob: Job? = null

    private enum class CastMode { SEGMENTED_AV1, SEGMENTED, WHOLE_FILE, MUXED }

    // In memory only: the first cast to a renderer after an app start probes the ladder again.
    private val failedCastModes = ConcurrentHashMap<String, MutableSet<CastMode>>()
    private var castJob: Job? = null

    // ── Discovery ──────────────────────────────────────────────────────────────

    /** Searches the network with [SsdpDiscovery] and lists each renderer once it is resolved. */
    fun startDiscovery(context: Context) {
        discoveryJob?.cancel()
        _devices.value = emptyList()

        // Start the proxy server when discovery begins
        proxy.start()

        discoveryJob =
            scope.launch {
                _isDiscovering.value = true
                try {
                    discovery.discover(context, ::addResolvedDevice)
                } finally {
                    _isDiscovering.value = false
                }
            }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        _isDiscovering.value = false
    }

    private fun addResolvedDevice(resolved: DlnaDevice) {
        val current = _devices.value.toMutableList()
        val idx = current.indexOfFirst { it.usn.substringBefore("::") == resolved.usn.substringBefore("::") }
        if (idx >= 0) current[idx] = resolved else current.add(resolved)
        _devices.value = current
    }

    // ── Control ────────────────────────────────────────────────────────────────

    /**
     * Casts video to a DLNA renderer.
     *
     * YouTube's googlevideo.com URLs are IP-locked and session-bound.
     * DLNA renderers can't fetch them directly (403 Forbidden).
     * The proxy runs on the phone and relays the stream, so the renderer
     * connects to the phone instead of YouTube.
     *
     * Tries each [CastMode] in order, skipping those a renderer already failed: AV1 goes above
     * 1080p but most renderers can't decode it, segmented fMP4 HLS starts in seconds but older
     * renderers can't play it, single-segment HLS works there but downloads the whole file first,
     * and a pre-muxed stream (or [currentPlayerUrl]) is the last resort.
     *
     * @param currentPlayerUrl the currently-playing media URI, supplied by the caller so this
     *   manager stays decoupled from the player engine; used as a last-resort fallback.
     * @return false when the video has no stream a renderer can be sent, so nothing is cast.
     */
    fun castStreamInfo(
        device: DlnaDevice,
        title: String,
        currentPlayerUrl: String?,
        videoOnlyStreams: List<VideoStream>,
        audioStreams: List<AudioStream>,
        muxedStreams: List<VideoStream>,
        durationSeconds: Long,
    ): Boolean {
        // Renderers fail playlists that mix codecs, so each playlist carries a single variant.
        fun bestVariants(codecKey: String): List<CastStreamVariant> =
            listOfNotNull(
                videoOnlyStreams
                    .filter { it.height > 0 && !(it.content ?: it.url).isNullOrEmpty() }
                    .filter { VideoCodecUtils.codecKeyFromStream(it) == codecKey }
                    // Renderers start on the first variant listed, and each one is probed before they get the playlist.
                    .maxByOrNull { VideoCodecUtils.qualityHeightFromStream(it) },
            ).map { stream ->
                CastStreamVariant(
                    url = stream.content ?: stream.url ?: "",
                    width = stream.width.takeIf { it > 0 } ?: (stream.height * 16 / 9),
                    height = stream.height,
                    bitrate = stream.bitrate.takeIf { it > 0 } ?: 2_500_000,
                    mime = "video/mp4",
                    codec = stream.codec?.takeIf { it.isNotBlank() } ?: "avc1.64001F",
                    segmentRanges = stream.castSegmentRanges(),
                )
            }
        val videoVariants = bestVariants("h264")
        // H.264 stops at 1080p, so AV1 is only worth trying when it goes higher.
        val av1Variants = bestVariants("av1").filter { it.height > (videoVariants.firstOrNull()?.height ?: 0) }

        val bestAudio =
            audioStreams
                .filter {
                    val mime = it.format?.mimeType ?: ""
                    mime.contains("mp4") || mime.contains("m4a") || mime.contains("aac")
                }.maxByOrNull { it.bitrate }

        val audioUrl = bestAudio?.let { it.content ?: it.url }
        val audioBitrate = bestAudio?.bitrate?.takeIf { it > 0 } ?: 128_000
        val audioCodec = bestAudio?.codec?.takeIf { it?.isNotBlank() == true } ?: "mp4a.40.2"
        val audioMime =
            bestAudio?.format?.mimeType?.let {
                if (it.contains("mp4") || it.contains("m4a")) "audio/mp4" else it
            } ?: "audio/mp4"

        val bestMuxed =
            muxedStreams
                .filter { it.height > 0 }
                .maxByOrNull { VideoCodecUtils.qualityHeightFromStream(it) }
        val muxedUrl =
            (bestMuxed?.let { it.content ?: it.url } ?: currentPlayerUrl)
                ?.takeIf { it.isNotEmpty() && !it.startsWith("local://") }

        val audioSegmentRanges = bestAudio?.castSegmentRanges()
        val canHls = videoVariants.isNotEmpty() && audioUrl != null
        val modes =
            listOfNotNull(
                CastMode.SEGMENTED_AV1.takeIf { canHls && av1Variants.firstOrNull()?.segmentRanges != null && audioSegmentRanges != null },
                CastMode.SEGMENTED.takeIf { canHls && videoVariants.first().segmentRanges != null && audioSegmentRanges != null },
                CastMode.WHOLE_FILE.takeIf { canHls },
                CastMode.MUXED.takeIf { muxedUrl != null },
            )
        val deviceKey = device.usn.substringBefore("::")
        // A mode is skipped only once it failed on this renderer, never because a video lacked it.
        val ladder = (modes - failedCastModes[deviceKey].orEmpty()).ifEmpty { modes }
        if (ladder.isEmpty()) return false

        castJob?.cancel()
        castJob =
            scope.launch {
                val host = castHost(device)
                for (mode in ladder) {
                    val (variants, audioRanges) =
                        when (mode) {
                            CastMode.SEGMENTED_AV1 -> av1Variants to audioSegmentRanges
                            CastMode.SEGMENTED -> videoVariants to audioSegmentRanges
                            else -> videoVariants.map { it.copy(segmentRanges = null) } to null
                        }
                    val segmented = audioRanges != null
                    // A video that can't be registered says nothing about the renderer, so it isn't remembered.
                    val (castUrl, contentType) =
                        try {
                            when (mode) {
                                CastMode.MUXED -> {
                                    val type = guessContentType(muxedUrl!!, "video/mp4")
                                    proxy.registerStream(muxedUrl, host, type) to type
                                }

                                else -> {
                                    proxy.registerHlsCast(
                                        host = host,
                                        videoVariants = variants,
                                        audioUrl = audioUrl!!,
                                        audioMime = audioMime,
                                        audioBitrate = audioBitrate,
                                        audioCodec = audioCodec,
                                        audioSegmentRanges = audioRanges,
                                        durationSeconds = durationSeconds,
                                    ) to "application/vnd.apple.mpegurl"
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Cast $mode registration failed: ${e.message}")
                            continue
                        }
                    Log.i(TAG, "Casting $mode: $castUrl")
                    // Registration blocks, so a newer cast or stop() may have cancelled this one meanwhile.
                    ensureActive()
                    try {
                        startCast(device, title, castUrl, contentType)
                        if (!segmented || awaitPlaying(device)) return@launch
                        Log.w(TAG, "Renderer did not start $mode within ${SEGMENTED_START_TIMEOUT_MS}ms, falling back")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Cast $mode failed: ${e.message}")
                    }
                    failedCastModes.getOrPut(deviceKey) { ConcurrentHashMap.newKeySet() } += mode
                }
                _currentDevice.value = null
            }
        return true
    }

    /** The address the renderer can reach this phone on, for the stream URLs it is handed. */
    private fun castHost(device: DlnaDevice): String =
        CastNetwork.advertisedHostFor(
            rendererIp = runCatching { URL(device.location).host }.getOrNull(),
            addresses = CastNetwork.localAddresses(),
            answeredOn = device.localHost,
        ) ?: "127.0.0.1"

    private suspend fun startCast(
        device: DlnaDevice,
        title: String,
        castUrl: String,
        contentType: String,
    ) {
        // Renderers still holding a previous session reject SetAVTransportURI with UPnP 701.
        runCatching { stop(device) }
        // Some only finish stopping after answering, and still reject the URI until then.
        withTimeoutOrNull(STOP_TIMEOUT_MS) {
            while (transportState(device).let { it != null && it != "STOPPED" && it != "NO_MEDIA_PRESENT" }) delay(500)
        }
        setAVTransportUri(device, castUrl, title, contentType)
        delay(500)
        // Renderers that buffer before answering can time Play out and still start playback.
        runCatching { play(device) }.onFailure { Log.w(TAG, "Play failed: ${it.message}") }
        _currentDevice.value = device
        startPositionPolling(device)
    }

    private suspend fun awaitPlaying(device: DlnaDevice): Boolean =
        withTimeoutOrNull(SEGMENTED_START_TIMEOUT_MS) {
            while (transportState(device) != "PLAYING") delay(1_000)
            true
        } ?: false

    private fun transportState(device: DlnaDevice): String? =
        runCatching {
            val response =
                sendSoapWithResponse(
                    device.avTransportUrl,
                    "urn:schemas-upnp-org:service:AVTransport:1#GetTransportInfo",
                    soapAction("GetTransportInfo", "urn:schemas-upnp-org:service:AVTransport:1", "<InstanceID>0</InstanceID>"),
                )
            Regex("<CurrentTransportState>([^<]+)</CurrentTransportState>").find(response)?.groupValues?.get(1)
        }.getOrNull()

    fun stop() {
        castJob?.cancel()
        val device = _currentDevice.value ?: return
        scope.launch {
            try {
                stop(device)
            } catch (e: Exception) {
                Log.e(TAG, "stop failed: ${e.message}")
            } finally {
                positionPollingJob?.cancel()
                _currentDevice.value = null
                _castPosition.value = 0
                _castDuration.value = 0
            }
        }
    }

    /**
     * Stops playback and fully shuts down the proxy server.
     * Call this when the user explicitly disconnects from the device.
     */
    fun disconnect() {
        stop()
        proxy.stop()
    }

    fun pause() {
        val device = _currentDevice.value ?: return
        scope.launch {
            try {
                sendSoap(
                    device.avTransportUrl,
                    "urn:schemas-upnp-org:service:AVTransport:1#Pause",
                    soapAction(
                        "Pause",
                        "urn:schemas-upnp-org:service:AVTransport:1",
                        "<InstanceID>0</InstanceID>",
                    ),
                )
            } catch (e: Exception) {
                Log.e(TAG, "pause failed: ${e.message}")
            }
        }
    }

    fun seekTo(
        device: DlnaDevice,
        positionSeconds: Long,
    ) {
        scope.launch {
            try {
                val hours = positionSeconds / 3600
                val minutes = (positionSeconds % 3600) / 60
                val seconds = positionSeconds % 60
                val target = String.format("%02d:%02d:%02d", hours, minutes, seconds)

                sendSoap(
                    device.avTransportUrl,
                    "urn:schemas-upnp-org:service:AVTransport:1#Seek",
                    soapAction(
                        "Seek",
                        "urn:schemas-upnp-org:service:AVTransport:1",
                        "<InstanceID>0</InstanceID>" +
                            "<Unit>REL_TIME</Unit>" +
                            "<Target>$target</Target>",
                    ),
                )
            } catch (e: Exception) {
                Log.e(TAG, "seekTo failed: ${e.message}")
            }
        }
    }

    /**
     * Guesses the content type from URL parameters.
     * YouTube URLs often contain mime type info in the query string.
     */
    private fun guessContentType(
        url: String,
        fallback: String,
    ): String =
        when {
            url.contains("mime=video/mp4") || url.contains("mime=video%2Fmp4") -> "video/mp4"
            url.contains("mime=video/webm") || url.contains("mime=video%2Fwebm") -> "video/webm"
            url.contains("mime=audio/mp4") || url.contains("mime=audio%2Fmp4") -> "audio/mp4"
            url.contains("mime=audio/webm") || url.contains("mime=audio%2Fwebm") -> "audio/webm"
            url.contains("itag=18") -> "video/mp4"
            url.contains("itag=22") -> "video/mp4"
            url.contains("itag=37") -> "video/mp4"
            else -> fallback
        }

    private fun startPositionPolling(device: DlnaDevice) {
        positionPollingJob?.cancel()
        positionPollingJob =
            scope.launch {
                while (_currentDevice.value != null) {
                    try {
                        val body =
                            soapAction(
                                "GetPositionInfo",
                                "urn:schemas-upnp-org:service:AVTransport:1",
                                "<InstanceID>0</InstanceID>",
                            )
                        val response =
                            sendSoapWithResponse(
                                device.avTransportUrl,
                                "urn:schemas-upnp-org:service:AVTransport:1#GetPositionInfo",
                                body,
                            )
                        parsePositionInfo(response)
                    } catch (e: Exception) {
                        Log.d(TAG, "Position poll error: ${e.message}")
                    }
                    delay(1000)
                }
            }
    }

    private fun parsePositionInfo(xml: String) {
        val relTimeRegex = Regex("<RelTime>([^<]+)</RelTime>")
        val durationRegex = Regex("<TrackDuration>([^<]+)</TrackDuration>")

        relTimeRegex.find(xml)?.groupValues?.get(1)?.let { time ->
            _castPosition.value = parseTimeToSeconds(time)
        }
        durationRegex.find(xml)?.groupValues?.get(1)?.let { time ->
            _castDuration.value = parseTimeToSeconds(time)
        }
    }

    private fun parseTimeToSeconds(time: String): Long {
        val parts = time.split(":")
        if (parts.size != 3) return 0
        return try {
            val hours = parts[0].toLong()
            val minutes = parts[1].toLong()
            val seconds = parts[2].split(".")[0].toLong()
            hours * 3600 + minutes * 60 + seconds
        } catch (e: Exception) {
            0
        }
    }

    private fun setAVTransportUri(
        device: DlnaDevice,
        uri: String,
        title: String,
        contentType: String = "video/mp4",
    ) {
        val metadata = buildDidlLite(uri, title, contentType)
        val body =
            soapAction(
                "SetAVTransportURI",
                "urn:schemas-upnp-org:service:AVTransport:1",
                "<InstanceID>0</InstanceID>" +
                    "<CurrentURI>${escapeXml(uri)}</CurrentURI>" +
                    "<CurrentURIMetaData>${escapeXml(metadata)}</CurrentURIMetaData>",
            )
        sendSoap(
            device.avTransportUrl,
            "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI",
            body,
        )
    }

    private fun stop(device: DlnaDevice) {
        sendSoap(
            device.avTransportUrl,
            "urn:schemas-upnp-org:service:AVTransport:1#Stop",
            soapAction("Stop", "urn:schemas-upnp-org:service:AVTransport:1", "<InstanceID>0</InstanceID>"),
        )
    }

    private fun play(device: DlnaDevice) {
        val body =
            soapAction(
                "Play",
                "urn:schemas-upnp-org:service:AVTransport:1",
                "<InstanceID>0</InstanceID><Speed>1</Speed>",
            )
        sendSoap(
            device.avTransportUrl,
            "urn:schemas-upnp-org:service:AVTransport:1#Play",
            body,
        )
    }

    private fun sendSoap(
        url: String,
        action: String,
        body: String,
    ) {
        if (url.isBlank()) {
            Log.w(TAG, "sendSoap: empty control URL, skipping")
            return
        }
        val request =
            Request
                .Builder()
                .url(url)
                .addHeader("SOAPAction", "\"$action\"")
                .post(body.toRequestBody(SOAP_TYPE))
                .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val responseBody = response.body?.string().orEmpty()
                throw IllegalStateException("SOAP $action failed (${response.code}): $responseBody")
            }
        }
    }

    private fun sendSoapWithResponse(
        url: String,
        action: String,
        body: String,
    ): String {
        val request =
            Request
                .Builder()
                .url(url)
                .addHeader("SOAPAction", "\"$action\"")
                .post(body.toRequestBody(SOAP_TYPE))
                .build()
        return http.newCall(request).execute().use { response ->
            response.body?.string() ?: ""
        }
    }

    private fun soapAction(
        action: String,
        serviceType: String,
        args: String,
    ): String =
        """<?xml version="1.0"?>""" +
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """ +
            """s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">""" +
            """<s:Body>""" +
            """<u:$action xmlns:u="$serviceType">$args</u:$action>""" +
            """</s:Body></s:Envelope>"""

    private fun buildDidlLite(
        uri: String,
        title: String,
        contentType: String = "video/mp4",
    ): String =
        """<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" """ +
            """xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" """ +
            """xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/">""" +
            """<item id="1" parentID="0" restricted="0">""" +
            """<dc:title>${escapeXml(title)}</dc:title>""" +
            """<res protocolInfo="http-get:*:$contentType:*">${escapeXml(uri)}</res>""" +
            """<upnp:class>object.item.videoItem</upnp:class>""" +
            """</item></DIDL-Lite>"""

    private fun escapeXml(s: String) =
        s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
}
