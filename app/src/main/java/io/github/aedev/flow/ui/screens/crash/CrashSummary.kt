package io.github.aedev.flow.ui.screens.crash

import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val NEW_ISSUE_URL = "https://github.com/Flow-Tube/Flow/issues/new"
private const val APP_PACKAGE = "io.github.aedev.flow"
private const val MAX_URL_LOG_CHARS = 1_500
private const val MAX_URL_ACTUAL_CHARS = 300
private const val UNKNOWN_STEPS = "Not known. Flow crashed during normal use; the stack trace is under Logs."
private const val EXPECTED = "Flow keeps running."
private const val TOP_FRAMES = 6
private const val OUTER_FRAMES = 2
private const val CAUSE_FRAMES = 4
private const val MAX_CAUSE_HEADER_CHARS = 400
private const val CAUSED_BY = "Caused by: "
private const val FULL_TRACE = "Full Stack Trace:"
private const val FRAME_PREFIX = "at "
private const val R8_SOURCE = "r8-map-id-"
private const val SKIPPED_FRAMES = "..."
private val ThrowHelperClasses = setOf("Intrinsics", "InlineClassHelperKt", "PreconditionsKt", "Preconditions")
private val ReportTime: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
private val WrappedCause = Regex("""([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+(?:Exception|Error))(?::.*)?""", RegexOption.DOT_MATCHES_ALL)

/** The facts a person needs from a crash report, read back from the text the crash handler saved. */
data class CrashSummary(
    val exception: String,
    val message: String?,
    val location: String?,
    val appVersion: String?,
    val android: String?,
    val device: String?,
    val time: LocalDateTime?,
    val topFrames: List<String>,
    /** The innermost `Caused by` of a wrapped crash, its header line first, then its frames. */
    val rootCause: List<String> = emptyList(),
) {
    /**
     * The class a title names. Android rethrows some failures as `RuntimeException(cause)`, so the
     * innermost cause, or a cause named in the message, is what tells two such crashes apart.
     */
    val headline: String
        get() =
            rootCause.firstOrNull()?.removePrefix(CAUSED_BY)?.let(::exceptionName)
                ?: message?.let(::exceptionName)
                ?: exception
}

fun parseCrashSummary(report: String): CrashSummary {
    val lines = report.lines().map { it.trim() }

    fun value(label: String) =
        lines
            .firstOrNull { it.startsWith("$label:") }
            ?.substringAfter(':')
            ?.trim()
            ?.ifBlank { null }
    val trace = lines.indexOf(FULL_TRACE).let { start -> if (start < 0) lines else lines.drop(start + 1) }
    val firstCause = trace.indexOfFirst { it.startsWith(CAUSED_BY) }
    val frames = (if (firstCause < 0) trace else trace.take(firstCause)).filter { it.startsWith(FRAME_PREFIX) }
    val lastCause = trace.indexOfLast { it.startsWith(CAUSED_BY) }
    val rootCause =
        if (lastCause < 0) {
            emptyList()
        } else {
            listOf(trace[lastCause]) + trace.drop(lastCause + 1).takeWhile { it.startsWith(FRAME_PREFIX) }
        }
    val rootFrames = rootCause.drop(1)
    val ownFrame =
        rootFrames.firstOrNull { APP_PACKAGE in it }
            ?: frames.firstOrNull { APP_PACKAGE in it }
            ?: rootFrames.firstOrNull { !isThrowHelper(it) }
            ?: frames.firstOrNull { !isThrowHelper(it) }
            ?: rootFrames.firstOrNull()
            ?: frames.firstOrNull()
    val manufacturer = value("Manufacturer")
    val model = value("Model")
    return CrashSummary(
        exception = value("Exception")?.substringAfterLast('.') ?: "Crash",
        message = value("Message")?.takeUnless { it == "none" },
        location = ownFrame?.let(::frameLocation),
        appVersion = value("App Version"),
        android = value("Android"),
        device = listOfNotNull(manufacturer, model).joinToString(" ").ifBlank { null },
        time =
            lines.firstOrNull { it.startsWith("CRASH REPORT - ") }?.substringAfter(" - ")?.let {
                runCatching { LocalDateTime.parse(it, ReportTime) }.getOrNull()
            },
        topFrames = frames.headKeepingOwnFrame(TOP_FRAMES),
        rootCause = rootCause,
    )
}

private fun qualifiedMethod(frame: String): String = frame.removePrefix(FRAME_PREFIX).substringBefore('(')

/**
 * Where a frame points. A release build's frames all name the same `r8-map-id-…` file, and every
 * `require` failure throws from its line 3, so there the class and method tell crashes apart.
 */
private fun frameLocation(frame: String): String {
    val source = frame.substringAfterLast('(').substringBefore(')')
    if (!source.startsWith(R8_SOURCE)) return source
    val method = qualifiedMethod(frame)
    return method.substringBeforeLast('.').substringAfterLast('.') + "." + method.substringAfterLast('.')
}

private fun isThrowHelper(frame: String): Boolean {
    val method = qualifiedMethod(frame)
    val name = method.substringAfterLast('.')
    return name.startsWith("throw") || name.startsWith("require") ||
        method.substringBeforeLast('.').substringAfterLast('.') in ThrowHelperClasses
}

/** The first [count] frames, plus the first of Flow's own frames when it comes later. */
private fun List<String>.headKeepingOwnFrame(count: Int): List<String> {
    val own = drop(count).firstOrNull { APP_PACKAGE in it } ?: return take(count)
    return take(count) + SKIPPED_FRAMES + own
}

private fun exceptionName(header: String): String? =
    WrappedCause
        .matchEntire(header)
        ?.groupValues
        ?.get(1)
        ?.substringAfterLast('.')

/**
 * The bug form with everything that fits in a URL filled in. The full report is too long for a
 * URL, so the Logs field gets the top of it and the page copies the rest.
 *
 * The required text fields are answered from the crash itself, so someone who cannot say how it
 * happened can still send it in one tap and edit only what they know (#1189).
 */
fun CrashSummary.issueUrl(): String {
    // The version rides in the title too: a link opened outside the browser can arrive as a title alone (#1942).
    val title = "[Bug]: $headline" + (location?.let { " in $it" } ?: "") + (appVersion?.let { " (${it.substringBefore(' ')})" } ?: "")
    val error = exception + (message?.let { ": $it" } ?: "")
    val cause =
        rootCause
            .takeIf { it.isNotEmpty() }
            ?.let { listOf(it.first().take(MAX_CAUSE_HEADER_CHARS)) + it.drop(1).headKeepingOwnFrame(CAUSE_FRAMES) }
            ?.joinToString("\n")
    val top = (listOf(error) + if (cause == null) topFrames else topFrames.take(OUTER_FRAMES)).joinToString("\n")
    val log =
        if (cause == null) {
            top.take(MAX_URL_LOG_CHARS)
        } else {
            (top.take((MAX_URL_LOG_CHARS - cause.length - 1).coerceAtLeast(0)) + "\n" + cause).take(MAX_URL_LOG_CHARS)
        }
    val fields =
        listOfNotNull(
            "template" to "bug_report.yml",
            "title" to title,
            appVersion?.let { "app-version" to it.substringBefore(' ') },
            android?.let { "android-version" to it },
            device?.let { "device" to it },
            "steps" to UNKNOWN_STEPS,
            "expected" to EXPECTED,
            "actual" to "Flow crashed with $error".take(MAX_URL_ACTUAL_CHARS),
            "logs" to log,
        )
    return NEW_ISSUE_URL + "?" +
        fields.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")}" }
}
