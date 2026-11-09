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
    val frames = (if (firstCause < 0) trace else trace.take(firstCause)).filter { it.startsWith("at ") }
    val lastCause = trace.indexOfLast { it.startsWith(CAUSED_BY) }
    val rootCause =
        if (lastCause < 0) {
            emptyList()
        } else {
            listOf(trace[lastCause]) + trace.drop(lastCause + 1).takeWhile { it.startsWith("at ") }
        }
    val rootFrames = rootCause.drop(1)
    val ownFrame =
        rootFrames.firstOrNull { APP_PACKAGE in it }
            ?: frames.firstOrNull { APP_PACKAGE in it }
            ?: rootFrames.firstOrNull()
            ?: frames.firstOrNull()
    val manufacturer = value("Manufacturer")
    val model = value("Model")
    return CrashSummary(
        exception = value("Exception")?.substringAfterLast('.') ?: "Crash",
        message = value("Message")?.takeUnless { it == "none" },
        location = ownFrame?.substringAfterLast('(')?.substringBefore(')'),
        appVersion = value("App Version"),
        android = value("Android"),
        device = listOfNotNull(manufacturer, model).joinToString(" ").ifBlank { null },
        time =
            lines.firstOrNull { it.startsWith("CRASH REPORT - ") }?.substringAfter(" - ")?.let {
                runCatching { LocalDateTime.parse(it, ReportTime) }.getOrNull()
            },
        topFrames = frames.take(TOP_FRAMES),
        rootCause = rootCause,
    )
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
    val title = "[Bug]: $headline" + (location?.let { " in $it" } ?: "")
    val error = exception + (message?.let { ": $it" } ?: "")
    val cause =
        rootCause
            .takeIf { it.isNotEmpty() }
            ?.let { listOf(it.first().take(MAX_CAUSE_HEADER_CHARS)) + it.drop(1).take(CAUSE_FRAMES) }
            ?.joinToString("\n")
    val top = (listOf(error) + topFrames.take(if (cause == null) TOP_FRAMES else OUTER_FRAMES)).joinToString("\n")
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
