package io.github.aedev.flow.platform.memory

/** When the heap is full enough to be worth a breadcrumb, and what that breadcrumb says. */
internal object HeapPressure {
    const val SAMPLE_INTERVAL_MS = 30_000L
    private const val RECORD_FROM_PERCENT = 60
    private const val BYTES_PER_MB = 1024 * 1024

    fun isHigh(
        usedBytes: Long,
        maxBytes: Long,
    ): Boolean = maxBytes > 0 && usedBytes * 100 >= maxBytes * RECORD_FROM_PERCENT

    fun describe(
        usedBytes: Long,
        maxBytes: Long,
        gauges: Map<String, Int>,
    ): String =
        buildString {
            append("heap ")
                .append(usedBytes / BYTES_PER_MB)
                .append('/')
                .append(maxBytes / BYTES_PER_MB)
                .append(" MB")
            gauges.forEach { (name, value) -> append(", ").append(name).append(' ').append(value) }
        }
}
