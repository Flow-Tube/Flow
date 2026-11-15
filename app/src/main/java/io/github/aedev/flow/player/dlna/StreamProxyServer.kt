package io.github.aedev.flow.player.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local HTTP proxy server that relays YouTube streams to DLNA renderers.
 *
 * YouTube's googlevideo.com URLs are IP-locked and session-bound.
 * DLNA renderers (Kodi, smart TVs, etc.) can't fetch them directly
 * because the request comes from a different device/user-agent.
 *
 * This proxy runs on the phone, accepts HTTP requests from DLNA devices
 * on the local network, and relays the content from YouTube's CDN using
 * the phone's own network session.
 *
 * Architecture:
 * - Runs a minimal HTTP/1.1 server on a random port
 * - Supports Range requests (required for seeking on DLNA renderers)
 * - Supports HEAD requests (required for some renderers to probe content)
 * - Streams data in chunks without buffering the entire video in memory
 * - Handles multiple concurrent connections (audio + video streams)
 * - Generates HLS master playlists (.m3u8) with video variants + audio,
 *   allowing renderers to handle adaptive quality selection and
 *   synchronized audio/video playback natively.
 *
 * Usage:
 *   val proxy = StreamProxyServer.getInstance()
 *   proxy.start(context)
 *   // Single stream:
 *   val localUrl = proxy.registerStream(youtubeStreamUrl, contentType)
 *   // HLS (multiple video qualities + audio):
 *   val m3u8 = proxy.registerHlsCast(videoVariants, audioUrl, ...)
 *   proxy.stop()
 */
class StreamProxyServer private constructor() {
    companion object {
        private const val TAG = "StreamProxy"
        private const val BUFFER_SIZE = 64 * 1024 // 64KB chunks
        private const val MAX_CONNECTIONS = 8

        @Volatile
        private var instance: StreamProxyServer? = null

        fun getInstance(): StreamProxyServer =
            instance ?: synchronized(this) {
                instance ?: StreamProxyServer().also { instance = it }
            }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val http =
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private var localAddress: String = "127.0.0.1"
    private var localPort: Int = 0

    /**
     * Map of path → StreamEntry.
     * Path is a short random ID like "/s/abc123"
     * StreamEntry holds the real YouTube URL and content type.
     */
    private val streams = ConcurrentHashMap<String, StreamEntry>()

    private data class StreamEntry(
        val realUrl: String,
        val contentType: String,
        val contentLength: Long = -1,
    )

    private val hlsPlaylists = ConcurrentHashMap<String, String>()

    /**
     * Starts the proxy server on a random available port.
     * Must be called before registerStream().
     */
    fun start(context: Context) {
        if (isRunning.get()) return

        localAddress = getDeviceIpAddress(context)

        scope.launch {
            try {
                serverSocket = ServerSocket(0, MAX_CONNECTIONS, InetAddress.getByName("0.0.0.0"))
                localPort = serverSocket!!.localPort
                isRunning.set(true)

                Log.i(TAG, "Proxy started at http://$localAddress:$localPort")

                while (isRunning.get()) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        scope.launch {
                            handleClient(clientSocket)
                        }
                    } catch (e: SocketException) {
                        if (isRunning.get()) {
                            Log.e(TAG, "Accept error", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Proxy server error", e)
            } finally {
                isRunning.set(false)
            }
        }
    }

    /**
     * Stops the proxy server and clears all registered streams.
     */
    fun stop() {
        isRunning.set(false)
        streams.clear()
        hlsPlaylists.clear()
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing server socket", e)
        }
        serverSocket = null
        Log.i(TAG, "Proxy stopped")
    }

    /**
     * Registers a YouTube stream URL and returns a local proxy URL
     * that can be sent to DLNA devices.
     *
     * @param realUrl The actual googlevideo.com stream URL
     * @param contentType MIME type (e.g., "video/mp4", "audio/mp4")
     * @return Local URL like "http://192.168.1.5:8080/s/abc123.mp4"
     */
    fun registerStream(
        realUrl: String,
        contentType: String = "video/mp4",
    ): String {
        if (!isRunning.get()) {
            Log.w(TAG, "Proxy not running, cannot register stream")
            return realUrl
        }

        val pathId =
            java.util.UUID
                .randomUUID()
                .toString()
                .take(8)
        val path = "/s/$pathId.${CastPlaylists.segmentFileExtension(contentType)}"

        val contentLength = probeContentLength(realUrl)

        streams[path] = StreamEntry(realUrl, contentType, contentLength)

        val proxyUrl = "http://$localAddress:$localPort$path"
        Log.i(TAG, "Registered stream: $proxyUrl → ${realUrl.take(80)}...")
        return proxyUrl
    }

    // ── HLS Playlist Generation ───────────────────────────────────────────────

    /**
     * Registers multiple video quality variants + audio and generates an HLS
     * master playlist (.m3u8) that the DLNA renderer fetches.
     * @param videoVariants List of video quality options (360p, 720p, 1080p, etc.)
     * @param audioUrl      Direct audio-only stream URL (googlevideo.com)
     * @param audioMime     MIME type of the audio stream
     * @param audioBitrate  Bitrate of the audio stream in bps
     * @param audioCodec    Codec string for audio (e.g., "mp4a.40.2")
     * @param durationSeconds Approximate duration of the video in seconds
     * @return Local URL to the generated master .m3u8 playlist
     */
    fun registerHlsCast(
        videoVariants: List<CastStreamVariant>,
        audioUrl: String,
        audioMime: String = "audio/mp4",
        audioBitrate: Int = 128_000,
        audioCodec: String = "mp4a.40.2",
        audioSegmentRanges: CastSegmentRanges? = null,
        durationSeconds: Long = 0,
    ): String {
        if (!isRunning.get() || videoVariants.isEmpty()) {
            Log.w(TAG, "Proxy not running or no variants, cannot register HLS")
            return videoVariants.firstOrNull()?.url ?: ""
        }

        val sessionId =
            java.util.UUID
                .randomUUID()
                .toString()
                .take(8)
        val durationSec = if (durationSeconds > 0) durationSeconds else 7200
        val targetDuration = durationSec + 1

        val proxyAudioUrl = registerStream(audioUrl, audioMime)
        val audioMediaPath = "/h/audio_$sessionId.m3u8"
        val audioMediaPlaylist =
            audioSegmentRanges?.let { CastPlaylists.buildSegmentedMediaPlaylist(http, proxyAudioUrl, audioUrl, audioMime, it) }
                ?: CastPlaylists.buildMediaPlaylist(proxyAudioUrl, durationSec, targetDuration)
        hlsPlaylists[audioMediaPath] = audioMediaPlaylist

        val audioMediaUrl = "http://$localAddress:$localPort$audioMediaPath"

        data class VariantEntry(
            val mediaUrl: String,
            val variant: CastStreamVariant,
            val proxyUrl: String,
        )

        val variantEntries =
            videoVariants.mapIndexed { index, variant ->
                val proxyVideoUrl = registerStream(variant.url, variant.mime)
                val videoMediaPath = "/h/v${variant.height}p_${sessionId}_$index.m3u8"
                val videoMediaPlaylist =
                    variant.segmentRanges?.let {
                        CastPlaylists.buildSegmentedMediaPlaylist(http, proxyVideoUrl, variant.url, variant.mime, it)
                    } ?: CastPlaylists.buildMediaPlaylist(proxyVideoUrl, durationSec, targetDuration)
                hlsPlaylists[videoMediaPath] = videoMediaPlaylist

                val videoMediaUrl = "http://$localAddress:$localPort$videoMediaPath"
                VariantEntry(videoMediaUrl, variant, proxyVideoUrl)
            }

        val masterPlaylist =
            CastPlaylists.buildMasterPlaylist(
                variantEntries = variantEntries.map { Triple(it.mediaUrl, it.variant, it.proxyUrl) },
                audioMediaUrl = audioMediaUrl,
                audioBitrate = audioBitrate,
                audioCodec = audioCodec,
            )

        val masterPath = "/h/master_$sessionId.m3u8"
        hlsPlaylists[masterPath] = masterPlaylist

        val masterUrl = "http://$localAddress:$localPort$masterPath"
        Log.i(
            TAG,
            "Registered HLS cast: $masterUrl " +
                "(${variantEntries.size} variants, audio=${audioBitrate / 1000}kbps)",
        )
        variantEntries.forEach { entry ->
            Log.d(
                TAG,
                "  Variant: ${entry.variant.width}x${entry.variant.height} " +
                    "${entry.variant.bitrate / 1000}kbps",
            )
        }

        return masterUrl
    }

    // ── Request Handling ──────────────────────────────────────────────────────

    /**
     * Handles an incoming HTTP request from a DLNA renderer.
     * Supports GET (with optional Range header) and HEAD.
     */
    private fun handleClient(clientSocket: Socket) {
        try {
            clientSocket.soTimeout = 30_000

            val input = clientSocket.getInputStream()
            val output = clientSocket.getOutputStream()

            val requestLine = readLine(input) ?: return
            val headers = mutableMapOf<String, String>()

            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val colonIdx = line.indexOf(':')
                if (colonIdx > 0) {
                    headers[line.substring(0, colonIdx).trim().lowercase()] =
                        line.substring(colonIdx + 1).trim()
                }
            }

            Log.d(TAG, "Request: $requestLine")

            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                sendError(output, 400, "Bad Request")
                return
            }

            val method = parts[0].uppercase()
            // Renderers may append their own query parameters; the proxy keys purely on the path.
            val path = parts[1].substringBefore('?')

            // HLS playlist endpoint (/h/…)
            if (path.startsWith("/h/") && path.endsWith(".m3u8")) {
                val playlist = hlsPlaylists[path]
                if (playlist == null) {
                    Log.w(TAG, "Unknown HLS playlist path: $path")
                    sendError(output, 404, "Not Found")
                    return
                }
                when (method) {
                    "HEAD" -> handlePlaylistHead(output, playlist)
                    "GET" -> handlePlaylistGet(output, playlist)
                    else -> sendError(output, 405, "Method Not Allowed")
                }
                return
            }

            // Direct stream proxy endpoint (/s/…)
            val streamEntry = streams[path]
            if (streamEntry == null) {
                Log.w(TAG, "Unknown path: $path")
                sendError(output, 404, "Not Found")
                return
            }

            when (method) {
                "HEAD" -> handleHead(output, streamEntry)
                "GET" -> handleGet(output, streamEntry, headers["range"])
                else -> sendError(output, 405, "Method Not Allowed")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Client handler error: ${e.message}")
        } finally {
            try {
                clientSocket.close()
            } catch (_: Exception) {
            }
        }
    }

    // ── HLS playlist serving ──────────────────────────────────────────────────

    private fun handlePlaylistHead(
        output: OutputStream,
        playlist: String,
    ) {
        val bytes = playlist.toByteArray(Charsets.UTF_8)
        val headers =
            buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: application/vnd.apple.mpegurl\r\n")
                append("Content-Length: ${bytes.size}\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
        output.write(headers.toByteArray())
        output.flush()
    }

    private fun handlePlaylistGet(
        output: OutputStream,
        playlist: String,
    ) {
        val bytes = playlist.toByteArray(Charsets.UTF_8)
        val headers =
            buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: application/vnd.apple.mpegurl\r\n")
                append("Content-Length: ${bytes.size}\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
        output.write(headers.toByteArray())
        output.write(bytes)
        output.flush()
        Log.d(TAG, "Served HLS playlist (${bytes.size} bytes)")
    }

    // ── Direct stream proxy ───────────────────────────────────────────────────

    /**
     * Handles HEAD requests. DLNA renderers use this to probe
     * content type and length before starting playback.
     */
    private fun handleHead(
        output: OutputStream,
        entry: StreamEntry,
    ) {
        val headers =
            buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: ${entry.contentType}\r\n")
                if (entry.contentLength > 0) {
                    append("Content-Length: ${entry.contentLength}\r\n")
                }
                append("Accept-Ranges: bytes\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
        output.write(headers.toByteArray())
        output.flush()
    }

    /**
     * Handles GET requests with optional Range support.
     * Fetches the content from YouTube and streams it to the DLNA renderer.
     */
    private fun handleGet(
        output: OutputStream,
        entry: StreamEntry,
        rangeHeader: String?,
    ) {
        try {
            val requestBuilder = Request.Builder().url(entry.realUrl)

            if (rangeHeader != null) {
                requestBuilder.addHeader("Range", rangeHeader)
            }

            val response = http.newCall(requestBuilder.build()).execute()

            if (!response.isSuccessful && response.code != 206) {
                Log.e(TAG, "YouTube returned ${response.code} for ${entry.realUrl.take(80)}")
                sendError(output, response.code, "Upstream Error")
                response.close()
                return
            }

            val body =
                response.body ?: run {
                    sendError(output, 502, "No Body")
                    response.close()
                    return
                }

            val responseHeaders =
                buildString {
                    if (response.code == 206) {
                        append("HTTP/1.1 206 Partial Content\r\n")
                        response.header("Content-Range")?.let {
                            append("Content-Range: $it\r\n")
                        }
                    } else {
                        append("HTTP/1.1 200 OK\r\n")
                    }

                    val contentType = response.header("Content-Type") ?: entry.contentType
                    append("Content-Type: $contentType\r\n")

                    val contentLength = response.header("Content-Length")
                    if (contentLength != null) {
                        append("Content-Length: $contentLength\r\n")
                    }

                    append("Accept-Ranges: bytes\r\n")
                    append("Access-Control-Allow-Origin: *\r\n")
                    append("Connection: close\r\n")
                    append("transferMode.dlna.org: Streaming\r\n")
                    append("contentFeatures.dlna.org: DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n")
                    append("\r\n")
                }

            output.write(responseHeaders.toByteArray())
            output.flush()

            val buffer = ByteArray(BUFFER_SIZE)
            val inputStream = body.byteStream()
            var bytesWritten = 0L

            while (true) {
                val read = inputStream.read(buffer)
                if (read == -1) break
                try {
                    output.write(buffer, 0, read)
                    bytesWritten += read
                } catch (e: SocketException) {
                    Log.d(TAG, "Client disconnected after ${bytesWritten / 1024}KB")
                    break
                }
            }

            output.flush()
            response.close()
            Log.d(TAG, "Streamed ${bytesWritten / 1024}KB to renderer")
        } catch (e: Exception) {
            Log.e(TAG, "GET handler error: ${e.message}")
            try {
                sendError(output, 502, "Proxy Error")
            } catch (_: Exception) {
            }
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private fun sendError(
        output: OutputStream,
        code: Int,
        message: String,
    ) {
        val response = "HTTP/1.1 $code $message\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        try {
            output.write(response.toByteArray())
            output.flush()
        } catch (_: Exception) {
        }
    }

    private fun probeContentLength(url: String): Long =
        try {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .head()
                    .build()
            val response = http.newCall(request).execute()
            val length = response.header("Content-Length")?.toLongOrNull() ?: -1
            response.close()
            length
        } catch (e: Exception) {
            Log.d(TAG, "Content length probe failed: ${e.message}")
            -1
        }

    private fun getDeviceIpAddress(context: Context): String {
        try {
            val wifiManager =
                context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wifiInfo = wifiManager?.connectionInfo
            val ip = wifiInfo?.ipAddress ?: 0
            if (ip != 0) {
                return String.format(
                    "%d.%d.%d.%d",
                    ip and 0xff,
                    (ip shr 8) and 0xff,
                    (ip shr 16) and 0xff,
                    (ip shr 24) and 0xff,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get WiFi IP", e)
        }

        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: continue
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enumerate interfaces", e)
        }

        return "127.0.0.1"
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\r'.code) {
                val next = input.read()
                if (next == '\n'.code) return sb.toString()
                sb.append('\r')
                if (next != -1) sb.append(next.toChar())
            } else {
                sb.append(b.toChar())
            }
        }
    }
}
