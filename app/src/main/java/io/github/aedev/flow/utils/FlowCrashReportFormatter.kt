package io.github.aedev.flow.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Deque
import java.util.Locale

data class FlowCrashBreadcrumb(
    val timestampMs: Long,
    val phase: String,
    val detail: String,
    val heapMb: Long? = null,
    /** How many times in a row this phase and detail were recorded; [timestampMs] is the latest. */
    val count: Int = 1,
)

data class FlowCrashReportSnapshot(
    val timestampMs: Long,
    val threadName: String,
    val threadId: Long,
    val exceptionClass: String,
    val exceptionMessage: String?,
    val stackTrace: String,
    val deviceInfo: String,
    val memoryInfo: String,
    val breadcrumbs: List<FlowCrashBreadcrumb>,
)

/** Adds [entry], folding it into the last one when it repeats it, so a burst can't push older history out. */
internal fun Deque<FlowCrashBreadcrumb>.record(
    entry: FlowCrashBreadcrumb,
    max: Int,
) {
    val last = peekLast()
    if (last != null && last.phase == entry.phase && last.detail == entry.detail) {
        pollLast()
        addLast(entry.copy(count = last.count + 1))
    } else {
        addLast(entry)
    }
    while (size > max) pollFirst()
}

object FlowCrashReportFormatter {
    private const val TOP_FRAME_COUNT = 8
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun build(snapshot: FlowCrashReportSnapshot): String =
        buildString {
            appendLine("=".repeat(60))
            appendLine("CRASH REPORT - ${dateFormat.format(Date(snapshot.timestampMs))}")
            appendLine("=".repeat(60))
            appendLine()
            appendLine("Crash Input")
            appendLine("Thread: ${snapshot.threadName} (id=${snapshot.threadId})")
            appendLine("Exception: ${snapshot.exceptionClass}")
            appendLine("Message: ${snapshot.exceptionMessage ?: "none"}")
            appendLine("OutOfMemoryError: ${snapshot.exceptionClass == OutOfMemoryError::class.java.name}")
            appendLine()
            appendLine("Device Info:")
            appendLine(snapshot.deviceInfo)
            appendLine()
            appendLine("Memory State:")
            appendLine(snapshot.memoryInfo)
            appendLine()
            appendLine("State/Cache Updates Before Crash")
            appendBreadcrumbs(snapshot.breadcrumbs)
            appendLine()
            appendLine("Final Output")
            appendLine("Top Stack Frames (same build):")
            topStackFrames(snapshot.stackTrace).forEach { appendLine(it) }
            appendLine()
            appendLine("Full Stack Trace:")
            appendLine(snapshot.stackTrace)
        }

    fun topStackFrames(
        stackTrace: String,
        limit: Int = TOP_FRAME_COUNT,
    ): List<String> =
        stackTrace
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("at ") || it.startsWith("Caused by:") }
            .take(limit)
            .toList()

    private fun StringBuilder.appendBreadcrumbs(breadcrumbs: List<FlowCrashBreadcrumb>) {
        if (breadcrumbs.isEmpty()) {
            appendLine("No lifecycle or state breadcrumbs recorded.")
            return
        }

        breadcrumbs.forEach { entry ->
            append("${timeFormat.format(Date(entry.timestampMs))} ${entry.phase}: ${entry.detail}")
            if (entry.count > 1) append(" ×${entry.count}")
            entry.heapMb?.let { append(" heap=${it}MB") }
            appendLine()
        }
    }
}
