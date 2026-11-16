package io.github.aedev.flow.data.video.downloader.tags

import androidx.media3.common.C
import io.github.aedev.flow.data.video.downloader.tags.Mp4Boxes.Span
import java.io.ByteArrayOutputStream

/**
 * The Spherical Video V2 boxes (`st3d`, `sv3d`) that mark a video track as 360°, built from the
 * projection Media3 read from the source stream, and placed in the MP4's video sample entry where
 * players look for them.
 *
 * Hand-rolled on purpose: media3-muxer 1.11.0 `Mp4Muxer` writes no projection boxes (none of
 * `sv3d`, `st3d`, `proj` or `projectionData` occurs in its classes), and platform `MediaExtractor`
 * drops the projection, so a remuxed 360° download would otherwise play flat (#1967).
 */
internal object Mp4Projection {
    private const val PROJ = "proj"
    private const val VISUAL_SAMPLE_ENTRY_FIELDS = 78
    private const val STSD_FIELDS = 8
    private const val HANDLER_TYPE_OFFSET = 8
    private const val MESH_ENCODING_OFFSET = 8
    private const val EQUI_PAYLOAD_BYTES = 20
    private const val CBMP_PAYLOAD_BYTES = 12
    private val MESH_ENCODINGS = setOf("raw ", "dfl8")

    /**
     * The boxes for a stream whose Media3 `Format` carries [projectionData] and [stereoMode], or null
     * when the projection is not one this can describe.
     *
     * MP4 sources hand over the whole `proj` box. WebM sources hand over the `ProjectionPrivate`
     * element, which by the WebM spec holds the payload of the matching `equi`, `cbmp` or `mshp` box;
     * Media3 drops the `ProjectionType`, so the kind is told apart by that payload's fixed layout.
     */
    fun boxes(
        projectionData: ByteArray,
        stereoMode: Int,
    ): ByteArray? {
        val proj = projBox(projectionData) ?: return null
        val svhd = fullBox("svhd", byteArrayOf(0))
        val sv3d = box("sv3d", svhd + proj)
        val st3d = stereoMode.takeIf { it in STEREO_MODES }?.let { fullBox("st3d", byteArrayOf(it.toByte())) }
        return (st3d ?: ByteArray(0)) + sv3d
    }

    /** [moov] with [boxes] added to its video sample entry, or null when it has none or is already marked. */
    fun addToVideoTrack(
        moov: ByteArray,
        headerSize: Int,
        boxes: ByteArray,
    ): ByteArray? {
        val trak = Mp4Boxes.children(moov, headerSize, moov.size).firstOrNull { it.type == "trak" && isVideo(moov, it) } ?: return null
        val newTrak =
            edit(Mp4Boxes.slice(moov, trak), listOf("mdia", "minf", "stbl", "stsd")) { stsd -> addToEntry(stsd, boxes) } ?: return null
        return replace(moov, trak, newTrak)
    }

    private fun projBox(projectionData: ByteArray): ByteArray? {
        if (projectionData.size >= Mp4Boxes.HEADER_SIZE && typeAt(projectionData, 4) == PROJ) {
            val spans = Mp4Boxes.children(projectionData, 0, projectionData.size)
            return projectionData.takeIf { spans.size == 1 && spans[0].size == projectionData.size.toLong() }
        }
        val kind =
            when {
                projectionData.size >= MESH_ENCODING_OFFSET + 4 && typeAt(projectionData, MESH_ENCODING_OFFSET) in MESH_ENCODINGS -> "mshp"
                projectionData.size == EQUI_PAYLOAD_BYTES -> "equi"
                projectionData.size == CBMP_PAYLOAD_BYTES -> "cbmp"
                else -> return null
            }
        val prhd = fullBox("prhd", ByteArray(12))
        return box(PROJ, prhd + box(kind, projectionData))
    }

    private fun isVideo(
        moov: ByteArray,
        trak: Span,
    ): Boolean {
        val trakBytes = Mp4Boxes.slice(moov, trak)
        val mdia = child(trakBytes, "mdia") ?: return false
        val mdiaBytes = Mp4Boxes.slice(trakBytes, mdia)
        val hdlr = child(mdiaBytes, "hdlr") ?: return false
        val typeOffset = hdlr.payloadStart.toInt() + HANDLER_TYPE_OFFSET
        return hdlr.end >= typeOffset + 4 && typeAt(mdiaBytes, typeOffset) == "vide"
    }

    private fun addToEntry(
        stsd: ByteArray,
        boxes: ByteArray,
    ): ByteArray? {
        val entriesStart = Mp4Boxes.headerSize(stsd) + STSD_FIELDS
        val entry = Mp4Boxes.children(stsd, entriesStart, stsd.size).firstOrNull() ?: return null
        val entryBytes = Mp4Boxes.slice(stsd, entry)
        val childrenStart = entry.headerSize + VISUAL_SAMPLE_ENTRY_FIELDS
        if (childrenStart > entryBytes.size) return null
        if (Mp4Boxes.children(entryBytes, childrenStart, entryBytes.size).any { it.type == "sv3d" || it.type == "st3d" }) return null
        val newEntry = box(entry.type, entryBytes.copyOfRange(entry.headerSize, entryBytes.size) + boxes)
        return replace(stsd, entry, newEntry)
    }

    /** Follows [path] down from [container], applies [change] to the last box, and rebuilds every box on the way. */
    private fun edit(
        container: ByteArray,
        path: List<String>,
        change: (ByteArray) -> ByteArray?,
    ): ByteArray? {
        val target = child(container, path.first()) ?: return null
        val targetBytes = Mp4Boxes.slice(container, target)
        val changed = (if (path.size == 1) change(targetBytes) else edit(targetBytes, path.drop(1), change)) ?: return null
        return replace(container, target, changed)
    }

    private fun child(
        container: ByteArray,
        type: String,
    ): Span? = Mp4Boxes.children(container, Mp4Boxes.headerSize(container), container.size).firstOrNull { it.type == type }

    private fun replace(
        container: ByteArray,
        old: Span,
        new: ByteArray,
    ): ByteArray {
        val header = Mp4Boxes.headerSize(container)
        val payload = ByteArrayOutputStream(container.size - old.size.toInt() + new.size)
        payload.write(container, header, old.start.toInt() - header)
        payload.write(new)
        payload.write(container, old.end.toInt(), container.size - old.end.toInt())
        return box(typeAt(container, 4), payload.toByteArray())
    }

    private fun box(
        type: String,
        payload: ByteArray,
    ): ByteArray = IlstAtoms.box(IlstAtoms.type(type), payload)

    private fun fullBox(
        type: String,
        payload: ByteArray,
    ): ByteArray = box(type, ByteArray(4) + payload)

    private fun typeAt(
        bytes: ByteArray,
        offset: Int,
    ): String = String(bytes, offset, 4, Charsets.ISO_8859_1)

    private val STEREO_MODES = setOf(C.STEREO_MODE_MONO, C.STEREO_MODE_TOP_BOTTOM, C.STEREO_MODE_LEFT_RIGHT, C.STEREO_MODE_STEREO_MESH)
}
