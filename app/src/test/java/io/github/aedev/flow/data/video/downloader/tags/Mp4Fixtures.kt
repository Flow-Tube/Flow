package io.github.aedev.flow.data.video.downloader.tags

import java.nio.ByteBuffer

/** Builds and parses tiny synthetic MP4 files for the tag writer tests. */
internal object Mp4Fixtures {
    data class Box(
        val type: String,
        val start: Int,
        val headerSize: Int,
        val size: Long,
        val bytes: ByteArray,
    ) {
        val payloadStart: Int get() = start + headerSize
        val end: Int get() = (start + size).toInt()

        fun children(payloadOffset: Int = 0): List<Box> = parse(bytes, payloadStart + payloadOffset, end)

        fun child(
            type: String,
            payloadOffset: Int = 0,
        ): Box? = children(payloadOffset).firstOrNull { it.type == type }

        fun payload(): ByteArray = bytes.copyOfRange(payloadStart, end)
    }

    data class Layout(
        val bytes: ByteArray,
        val mdatPayload: ByteArray,
    )

    val MDAT_PAYLOAD: ByteArray = ByteArray(4_096) { (it * 31 + 7).toByte() }
    private const val SAMPLE_COUNT = 8

    fun box(
        type: String,
        vararg payload: ByteArray,
    ): ByteArray {
        val body = payload.fold(ByteArray(0)) { acc, part -> acc + part }
        return ByteBuffer
            .allocate(8 + body.size)
            .putInt(8 + body.size)
            .put(type.toByteArray(Charsets.ISO_8859_1))
            .put(body)
            .array()
    }

    fun largeBox(
        type: String,
        payload: ByteArray,
    ): ByteArray =
        ByteBuffer
            .allocate(16 + payload.size)
            .putInt(1)
            .put(type.toByteArray(Charsets.ISO_8859_1))
            .putLong(16L + payload.size)
            .put(payload)
            .array()

    fun openEndedBox(
        type: String,
        payload: ByteArray,
    ): ByteArray =
        ByteBuffer
            .allocate(8 + payload.size)
            .putInt(0)
            .put(type.toByteArray(Charsets.ISO_8859_1))
            .put(payload)
            .array()

    fun ftyp(): ByteArray = box("ftyp", "isom".ascii(), int(0x200), "isomiso2mp41".ascii())

    fun moov(
        chunkOffsets: List<Long>,
        vararg extraChildren: ByteArray,
        largeHeader: Boolean = false,
    ): ByteArray {
        val stco = box("stco", int(0), int(chunkOffsets.size), *chunkOffsets.map { int(it.toInt()) }.toTypedArray())
        val trak = box("trak", box("tkhd", ByteArray(84)), box("mdia", box("minf", box("stbl", stco))))
        val body = box("mvhd", ByteArray(100)) + trak + extraChildren.fold(ByteArray(0)) { acc, part -> acc + part }
        return if (largeHeader) largeBox("moov", body) else box("moov", body)
    }

    /** `ftyp | mdat | moov`, the layout Mp4Muxer writes with streamable output off. */
    fun moovLast(
        largeMdat: Boolean = false,
        largeMoov: Boolean = false,
        vararg moovExtras: ByteArray,
    ): Layout {
        val ftyp = ftyp()
        val mdat = if (largeMdat) largeBox("mdat", MDAT_PAYLOAD) else box("mdat", MDAT_PAYLOAD)
        val mdatPayloadStart = ftyp.size + (mdat.size - MDAT_PAYLOAD.size)
        val moov = moov(sampleOffsets(mdatPayloadStart.toLong()), *moovExtras, largeHeader = largeMoov)
        return Layout(ftyp + mdat + moov, MDAT_PAYLOAD)
    }

    /**
     * `ftyp | moov | mdat`, a "fast start" file; [openEndedMdat] leaves the mdat size at 0, and
     * [freeAfterMoov] puts a `free` box of that size between `moov` and `mdat`, as Mp4Muxer reserves.
     */
    fun moovFirst(
        openEndedMdat: Boolean = false,
        vararg moovExtras: ByteArray,
        freeAfterMoov: Int = 0,
    ): Layout {
        val ftyp = ftyp()
        val free = if (freeAfterMoov > 0) box("free", ByteArray(freeAfterMoov - 8)) else ByteArray(0)
        val moovSize = moov(sampleOffsets(0), *moovExtras).size
        val mdatPayloadStart = ftyp.size + moovSize + free.size + 8
        val moov = moov(sampleOffsets(mdatPayloadStart.toLong()), *moovExtras)
        val mdat = if (openEndedMdat) openEndedBox("mdat", MDAT_PAYLOAD) else box("mdat", MDAT_PAYLOAD)
        return Layout(ftyp + moov + free + mdat, MDAT_PAYLOAD)
    }

    fun parse(
        bytes: ByteArray,
        from: Int = 0,
        to: Int = bytes.size,
    ): List<Box> {
        val boxes = mutableListOf<Box>()
        var position = from
        while (position + 8 <= to) {
            val buffer = ByteBuffer.wrap(bytes, position, to - position)
            val size32 = buffer.int.toLong() and 0xFFFFFFFFL
            val type = String(bytes, position + 4, 4, Charsets.ISO_8859_1)
            val (headerSize, size) =
                when (size32) {
                    0L -> 8 to (to - position).toLong()
                    1L -> 16 to ByteBuffer.wrap(bytes, position + 8, 8).long
                    else -> 8 to size32
                }
            check(size >= headerSize && position + size <= to) { "Bad box '$type' at $position size $size" }
            boxes += Box(type, position, headerSize, size, bytes)
            position += size.toInt()
        }
        return boxes
    }

    fun find(
        bytes: ByteArray,
        vararg path: String,
    ): Box? {
        var candidates = parse(bytes)
        var current: Box? = null
        path.forEach { type ->
            current = candidates.firstOrNull { it.type == type } ?: return null
            val offset = if (type == "meta") 4 else 0
            candidates = current!!.children(offset)
        }
        return current
    }

    fun chunkOffsets(bytes: ByteArray): List<Long> {
        val stco = find(bytes, "moov", "trak", "mdia", "minf", "stbl", "stco") ?: error("no stco")
        val payload = ByteBuffer.wrap(stco.payload())
        payload.int
        return List(payload.int) { payload.int.toLong() and 0xFFFFFFFFL }
    }

    /** The UTF-8 value of `ilst/<type>/data`. */
    fun ilstText(
        ilst: Box,
        type: String,
    ): String? = ilstData(ilst, type)?.let { String(it.second, Charsets.UTF_8) }

    /** The (data type, value) of `ilst/<type>/data`. */
    fun ilstData(
        ilst: Box,
        type: String,
    ): Pair<Int, ByteArray>? {
        val atom = ilst.children().firstOrNull { it.type == type } ?: return null
        val data = atom.child("data") ?: return null
        val payload = data.payload()
        return ByteBuffer.wrap(payload).int to payload.copyOfRange(8, payload.size)
    }

    private fun sampleOffsets(mdatPayloadStart: Long): List<Long> =
        List(SAMPLE_COUNT) { mdatPayloadStart + it * (MDAT_PAYLOAD.size / SAMPLE_COUNT) }

    private fun int(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()

    private fun String.ascii(): ByteArray = toByteArray(Charsets.ISO_8859_1)
}
