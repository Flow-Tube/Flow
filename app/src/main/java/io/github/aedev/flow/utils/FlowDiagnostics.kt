package io.github.aedev.flow.utils

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Tags that repeat dozens of times per video surface and push real errors out of a report. */
private val SILENCED_LOG_TAGS = listOf("BufferQueueProducer", "Codec2Client")

/** Mali driver lines log under the process name, so they cannot be silenced by tag. */
internal fun withoutDriverNoise(logs: String): String = logs.lineSequence().filterNot { "MALI DEBUG" in it }.joinToString("\n")

/**
 * On-demand diagnostics helper.
 *
 * Reads logcat output for the current process (no special permissions required —
 * apps may always read their own PID's logs since API 18) and surfaces crash
 * reports that FlowCrashHandler persisted to disk.
 */
object FlowDiagnostics {
    private const val TAG = "FlowDiagnostics"
    private const val RECENT_EXITS = 5

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Reads recent Logcat entries for this app's process at WARNING level and
     * above.  Blocks the calling thread; run on [kotlinx.coroutines.Dispatchers.IO].
     *
     * @param maxLines Maximum number of log lines to return (default 600).
     */
    fun readSessionLogs(maxLines: Int = 600): String =
        try {
            val pid = android.os.Process.myPid()
            // --pid restricts output to this process only; *:W = WARN level and above.
            val process =
                ProcessBuilder(
                    listOf("logcat", "--pid=$pid", "-d", "-t", maxLines.toString()) +
                        SILENCED_LOG_TAGS.map { "$it:S" } +
                        "*:W",
                ).redirectErrorStream(true)
                    .start()
            val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
            process.waitFor()
            withoutDriverNoise(output).ifBlank { "No warnings or errors found in this session." }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read session logcat", e)
            "Unable to read session logs: ${e.message}\n\n" +
                "This can happen on some heavily customized Android skins."
        }

    /**
     * Returns crash reports written to disk by [FlowCrashHandler].
     * Call on any thread — file I/O is minimal (single small text file).
     */
    fun getCrashLogs(context: Context): String = FlowCrashHandler.getCrashLogs(context)

    /** Deletes the on-disk crash log file. */
    fun clearCrashLogs(context: Context) = FlowCrashHandler.clearCrashLogs(context)

    /**
     * Assembles a single shareable text report containing device metadata,
     * session logs, and any persisted crash reports.
     */
    fun buildFullReport(
        context: Context,
        sessionLogs: String,
        playbackErrors: String? = null,
    ): String =
        buildString {
            val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US))
            appendLine("=".repeat(60))
            appendLine("FLOW DIAGNOSTICS REPORT")
            appendLine("Generated: $ts")
            appendLine("=".repeat(60))
            appendLine()
            appendLine(buildDeviceInfo(context))
            appendLine()
            appendLine("=".repeat(60))
            appendLine("SESSION LOGS  (W/E level, current session)")
            appendLine("=".repeat(60))
            appendLine(sessionLogs)
            playbackErrors?.takeIf(String::isNotBlank)?.let { errors ->
                appendLine()
                appendLine("=".repeat(60))
                appendLine("RECENT PLAYBACK ERRORS  (persisted across sessions)")
                appendLine("=".repeat(60))
                appendLine(errors)
            }
            recentExitsOrNull(context)?.let { exits ->
                appendLine()
                appendLine("=".repeat(60))
                appendLine("RECENT EXITS  (newest first, Android 11+)")
                appendLine("=".repeat(60))
                appendLine(exits)
            }
            val crashes = crashLogsOrNull(context)
            if (crashes != null) {
                appendLine()
                appendLine("=".repeat(60))
                appendLine("CRASH REPORTS  (persisted across sessions)")
                appendLine("=".repeat(60))
                appendLine(crashes)
            }
        }

    /** How the app's last processes ended, or null below Android 11 or when the system kept none. */
    fun recentExitsOrNull(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val exits =
            runCatching {
                context
                    .getSystemService(ActivityManager::class.java)
                    ?.getHistoricalProcessExitReasons(context.packageName, 0, RECENT_EXITS)
            }.getOrNull()
                .orEmpty()
        if (exits.isEmpty()) return null
        return exits.joinToString("\n") { ExitReasonText.line(it.reason, it.timestamp, it.importance, it.description) }
    }

    /** Persisted crash reports, or null when there are none. */
    fun crashLogsOrNull(context: Context): String? = getCrashLogs(context).takeUnless { it.isBlank() || it.trim() == NO_CRASH_LOGS }

    fun deviceInfo(context: Context): DeviceInfo {
        val packageInfo = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        return DeviceInfo(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            brand = Build.BRAND,
            androidRelease = Build.VERSION.RELEASE,
            sdk = Build.VERSION.SDK_INT,
            versionName = packageInfo?.versionName,
            versionCode = packageInfo?.let(PackageInfoCompat::getLongVersionCode),
        )
    }

    /** one-liner block of device + app version metadata. */
    fun buildDeviceInfo(context: Context): String =
        deviceInfo(context).let { info ->
            buildString {
                appendLine("Manufacturer : ${info.manufacturer}")
                appendLine("Model        : ${info.model}")
                appendLine("Android      : ${info.androidRelease} (SDK ${info.sdk})")
                appendLine("Brand        : ${info.brand}")
                appendLine("App version  : ${info.versionName?.let { "$it (${info.versionCode})" } ?: "unknown"}")
            }.trimEnd()
        }

    private const val NO_CRASH_LOGS = "No crash logs"
}

/** The device and build a diagnostics report describes. */
data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val brand: String,
    val androidRelease: String,
    val sdk: Int,
    val versionName: String?,
    val versionCode: Long?,
)
