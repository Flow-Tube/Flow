package io.github.aedev.flow.data.video.downloader.tags

import io.github.aedev.flow.data.video.downloader.tags.Mp4Boxes.Span
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import javax.inject.Inject

/**
 * Writes `moov/udta/meta(hdlr 'mdir')/ilst` into an MP4 so Android's MediaStore, Media3 and
 * desktop players show title, artist, album, track number, date, lyrics and cover art.
 *
 * Hand-rolled on purpose: nothing on the classpath can write `ilst`. media3-muxer 1.11.0
 * `Mp4Muxer.addMetadataEntry` accepts only `MdtaMetadataEntry` (string/float32), `XmpData`,
 * `Mp4LocationData`, `Mp4OrientationData` and `Mp4TimestampData` (`MuxerUtil.isMetadataSupported`),
 * its `WebmMuxer`/`OggMuxer` reject metadata, and platform `MediaMuxer` has no tag API.
 *
 * The `moov` box is replaced through [Mp4MoovFile], which never moves `mdat`. Operate on a staged
 * copy; the file is edited in place.
 */
class Mp4TagWriter
    @Inject
    constructor() {
        @Throws(IOException::class)
        fun write(
            file: File,
            tags: DownloadTags,
            cover: ByteArray? = null,
        ) {
            val ilst = IlstAtoms.build(tags, cover)
            Mp4MoovFile.edit(file) { moov, headerSize -> rebuildMoov(moov, headerSize, ilst) }
        }

        private fun rebuildMoov(
            moov: ByteArray,
            headerSize: Int,
            ilst: ByteArray,
        ): ByteArray {
            val children = Mp4Boxes.children(moov, headerSize, moov.size)
            val udta = children.firstOrNull { it.type == UDTA }
            val newUdta = rebuildUdta(udta?.let { Mp4Boxes.slice(moov, it) }, ilst)
            val out = ByteArrayOutputStream(moov.size + newUdta.size)
            children.forEach { child ->
                if (child === udta) out.write(newUdta) else out.write(moov, child.start.toInt(), child.size.toInt())
            }
            if (udta == null) out.write(newUdta)
            return IlstAtoms.box(IlstAtoms.type(MOOV), out.toByteArray())
        }

        private fun rebuildUdta(
            udta: ByteArray?,
            ilst: ByteArray,
        ): ByteArray {
            if (udta == null) return IlstAtoms.box(IlstAtoms.type(UDTA), rebuildMeta(null, ilst))
            val children = Mp4Boxes.children(udta, Mp4Boxes.headerSize(udta), udta.size)
            val meta =
                children.firstOrNull { it.type == META && handlerOf(Mp4Boxes.slice(udta, it)).let { h -> h == null || h == MDIR } }
            val out = ByteArrayOutputStream(udta.size + ilst.size)
            children.forEach { child ->
                if (child === meta) {
                    out.write(rebuildMeta(Mp4Boxes.slice(udta, child), ilst))
                } else {
                    out.write(udta, child.start.toInt(), child.size.toInt())
                }
            }
            if (meta == null) out.write(rebuildMeta(null, ilst))
            return IlstAtoms.box(IlstAtoms.type(UDTA), out.toByteArray())
        }

        private fun rebuildMeta(
            meta: ByteArray?,
            ilst: ByteArray,
        ): ByteArray {
            val kept = meta?.let { metaChildren(it) }.orEmpty()
            val out = ByteArrayOutputStream()
            out.write(ByteArray(4))
            if (kept.none { it.first.type == HDLR }) out.write(MDIR_HANDLER)
            var ilstWritten = false
            kept.forEach { (span, bytes) ->
                if (span.type == ILST) {
                    if (!ilstWritten) out.write(ilst)
                    ilstWritten = true
                } else {
                    out.write(bytes)
                }
            }
            if (!ilstWritten) out.write(ilst)
            return IlstAtoms.box(IlstAtoms.type(META), out.toByteArray())
        }

        private fun metaChildren(meta: ByteArray): List<Pair<Span, ByteArray>> =
            Mp4Boxes.children(meta, Mp4Boxes.metaChildrenStart(meta), meta.size).map { it to Mp4Boxes.slice(meta, it) }

        private fun handlerOf(meta: ByteArray): String? {
            val (hdlr, bytes) = metaChildren(meta).firstOrNull { it.first.type == HDLR } ?: return null
            val typeOffset = hdlr.headerSize + HANDLER_TYPE_OFFSET
            if (bytes.size < typeOffset + 4) return null
            return String(bytes, typeOffset, 4, Charsets.ISO_8859_1)
        }

        private companion object {
            const val MOOV = "moov"
            const val UDTA = "udta"
            const val META = "meta"
            const val HDLR = "hdlr"
            const val ILST = "ilst"
            const val MDIR = "mdir"
            const val HANDLER_TYPE_OFFSET = 8

            val MDIR_HANDLER: ByteArray =
                IlstAtoms.box(
                    IlstAtoms.type(HDLR),
                    ByteArray(8) + "mdirappl".toByteArray(Charsets.ISO_8859_1) + ByteArray(9),
                )
        }
    }
