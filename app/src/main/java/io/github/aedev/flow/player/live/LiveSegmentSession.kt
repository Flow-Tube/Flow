package io.github.aedev.flow.player.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * One live stream played from its segments: the formats, the URL each itag is fetched from, and the
 * clock that places segment numbers on Media3's timeline.
 *
 * @param refreshUrls a fresh player response's URLs by itag, for when these expire.
 */
class LiveSegmentSession internal constructor(
    val videoId: String,
    val formats: List<LiveSegmentFormat>,
    urls: Map<Int, String>,
    val clock: LiveSegmentClock,
    val readaheadMs: Long,
    serverClockOffsetMs: Long,
    private val deviceNow: () -> Long = System::currentTimeMillis,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    refreshUrls: suspend () -> Map<Int, String>? = { null },
    isStillLive: suspend () -> Boolean? = { null },
) {
    @Volatile
    private var serverClockOffsetMs = serverClockOffsetMs

    private val urls = LiveSegmentUrls(urls, scope, refreshUrls, serverNow = { deviceNow() + this.serverClockOffsetMs })
    private val end = LiveStreamEnd(clock.segmentDurationUs / 1_000, scope, isStillLive)

    /** The broadcast has ended; playback runs to its last segment and stops there. */
    val hasEnded: Boolean
        get() = end.lastSequenceOfEndedStream != null

    val manifestUri: String = "$SCHEME://$MANIFEST_HOST/$videoId"

    fun segmentUri(itag: Int): String = "$SCHEME://$SEGMENT_HOST/$videoId/$itag/"

    fun urlFor(itag: Int): String? = urls.urlFor(itag)

    /** A segment was refused, so the URLs are fetched again. */
    fun onRefused() = urls.onRefused()

    fun manifest(): String =
        LiveSegmentManifest.build(
            clock = clock,
            formats = formats,
            segmentUri = ::segmentUri,
            serverNowMs = deviceNow() + serverClockOffsetMs,
            lastSequence = end.lastSequenceOfEndedStream,
        )

    /** Feeds the head a segment response reported back into the clock. */
    fun observe(headers: Map<String, List<String>>) {
        val head = LiveHeadHeaders.parse(headers) ?: return
        serverClockOffsetMs = head.serverTimeMs - deviceNow()
        clock.observe(head)
        end.observe(head)
    }

    companion object {
        const val SCHEME = "flowlive"
        internal const val MANIFEST_HOST = "manifest"
        internal const val SEGMENT_HOST = "segment"
    }
}

/** Reads the head of a live stream from the headers googlevideo sends with every segment. */
internal object LiveHeadHeaders {
    private const val HEAD_SEQUENCE = "x-head-seqnum"
    private const val HEAD_TIME_MS = "x-head-time-millis"
    private const val SERVER_TIME_MS = "x-walltime-ms"

    fun parse(headers: Map<String, List<String>>): LiveHead? {
        fun value(name: String): Long? =
            headers.entries
                .firstOrNull { it.key.equals(name, ignoreCase = true) }
                ?.value
                ?.firstOrNull()
                ?.trim()
                ?.toLongOrNull()
        return LiveHead(
            sequence = value(HEAD_SEQUENCE) ?: return null,
            mediaTimeMs = value(HEAD_TIME_MS) ?: return null,
            serverTimeMs = value(SERVER_TIME_MS) ?: return null,
        )
    }
}
