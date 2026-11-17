package io.github.aedev.flow.player.resolver

import android.net.Uri
import android.text.TextUtils
import android.util.Pair
import androidx.media3.common.C
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.RangedUri
import androidx.media3.exoplayer.dash.manifest.Representation
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.File

/**
 * Lets the real Media3 source factories and DASH parser run on the JVM. The unit test android.jar
 * answers every framework call with a default, so `Uri.parse` is null (no media item gets a URI),
 * `TextUtils.isEmpty` is false (the parser then reads a missing frame rate denominator) and
 * `Pair.create` is null (the parser loses every period).
 */
internal object Media3JvmShims {
    fun install() {
        mockkStatic(Uri::class, TextUtils::class, Pair::class)
        every { Uri.parse(any()) } answers { fakeUri(firstArg()) }
        every { Uri.fromFile(any()) } answers { fakeUri("file://" + firstArg<File>().path) }
        every { TextUtils.isEmpty(any()) } answers { firstArg<CharSequence?>().isNullOrEmpty() }
        every { Pair.create<Any?, Any?>(any(), any()) } answers { pair(firstArg(), secondArg()) }
    }

    fun uninstall() {
        unmockkStatic(Uri::class, TextUtils::class, Pair::class)
    }

    // The stub constructor leaves both fields null, so they are written the way the real one would.
    private fun pair(
        first: Any?,
        second: Any?,
    ): Pair<Any?, Any?> =
        Pair<Any?, Any?>(first, second).also { pair ->
            Pair::class.java
                .getField("first")
                .apply { isAccessible = true }
                .set(pair, first)
            Pair::class.java
                .getField("second")
                .apply { isAccessible = true }
                .set(pair, second)
        }

    private fun fakeUri(value: String): Uri =
        mockk(relaxed = true) {
            every { this@mockk.toString() } returns value
            every { scheme } returns value.substringBefore(':', "")
            every { lastPathSegment } returns value.substringBefore('?').substringAfterLast('/')
        }
}

/** A media source reduced to what the player is handed: its kind, item and, for DASH, the manifest. */
internal data class SourceShape(
    val kind: String,
    val uri: String?,
    val mimeType: String?,
    val mediaId: String?,
    val liveTargetOffsetMs: Long? = null,
    val sideloadedManifest: ManifestShape? = null,
    val children: List<SourceShape> = emptyList(),
)

internal data class ManifestShape(
    val durationMs: Long,
    val representations: List<RepresentationShape>,
)

internal data class RepresentationShape(
    val trackType: Int,
    val id: String?,
    val mimeType: String?,
    val codecs: String?,
    val bitrate: Int,
    val width: Int,
    val height: Int,
    val frameRate: Float,
    val sampleRate: Int,
    val channels: Int,
    val baseUrl: String,
    val initRange: String?,
    val indexRange: String?,
)

private fun RangedUri.range() = "$start-${start + length - 1}"

internal fun DashManifest.shape(): ManifestShape =
    ManifestShape(
        durationMs = durationMs,
        representations =
            (0 until periodCount).flatMap { index ->
                getPeriod(index).adaptationSets.flatMap { set -> set.representations.map { it.shape(set.type) } }
            },
    )

private fun Representation.shape(trackType: Int) =
    RepresentationShape(
        trackType = trackType,
        id = format.id,
        mimeType = format.containerMimeType,
        codecs = format.codecs,
        bitrate = format.bitrate,
        width = format.width,
        height = format.height,
        frameRate = format.frameRate,
        sampleRate = format.sampleRate,
        channels = format.channelCount,
        baseUrl = baseUrls.first().url,
        initRange = initializationUri?.range(),
        indexRange = indexUri?.range(),
    )

internal fun MediaSource?.shape(): SourceShape? {
    val source = this ?: return null
    val item = runCatching { source.mediaItem }.getOrNull()
    val config = item?.localConfiguration
    val offset = item?.liveConfiguration?.targetOffsetMs?.takeIf { it != C.TIME_UNSET }
    return when (source) {
        is MergingMediaSource -> {
            @Suppress("UNCHECKED_CAST")
            val children =
                MergingMediaSource::class.java
                    .getDeclaredField("mediaSources")
                    .apply {
                        isAccessible = true
                    }.get(source) as Array<MediaSource>
            SourceShape("merging", null, null, null, children = children.mapNotNull { it.shape() })
        }

        is DashMediaSource -> {
            val manifest =
                DashMediaSource::class.java
                    .getDeclaredField(
                        "manifest",
                    ).apply { isAccessible = true }
                    .get(source) as DashManifest?
            SourceShape("dash", config?.uri?.toString(), config?.mimeType, item?.mediaId, offset, manifest?.shape())
        }

        is HlsMediaSource -> {
            SourceShape("hls", config?.uri?.toString(), config?.mimeType, item?.mediaId, offset)
        }

        is ProgressiveMediaSource -> {
            SourceShape("progressive", config?.uri?.toString(), config?.mimeType, item?.mediaId, offset)
        }

        else -> {
            SourceShape(source.javaClass.simpleName, config?.uri?.toString(), config?.mimeType, item?.mediaId, offset)
        }
    }
}
