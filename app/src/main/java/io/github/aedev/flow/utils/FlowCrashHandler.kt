package io.github.aedev.flow.utils

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.ConcurrentLinkedDeque

private const val CRASH_PREFS_NAME = "flow_crash_prefs"
private const val CRASH_PREFS_KEY_LAST = "last_crash"

/**
 * Global exception handler for crash monitoring and logging.
 * Catches uncaught exceptions and logs them
 */
class FlowCrashHandler private constructor(
    private val context: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    companion object {
        private const val TAG = "FlowCrashHandler"
        private const val CRASH_LOG_FILE = "flow_crashes.log"
        private const val MAX_CRASH_LOG_SIZE = 500_000L // 500KB
        private const val MAX_BREADCRUMBS = 40
        private val breadcrumbs = ConcurrentLinkedDeque<FlowCrashBreadcrumb>()

        @Volatile
        private var instance: FlowCrashHandler? = null

        /**
         * Install the crash handler. Call from Application.onCreate()
         */
        fun install(context: Context) {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
                        instance = FlowCrashHandler(context.applicationContext, defaultHandler)
                        Thread.setDefaultUncaughtExceptionHandler(instance)
                        Log.i(TAG, "Crash handler installed")
                    }
                }
            }
        }

        /**
         * Get the last crash that was persisted to SharedPreferences.
         * Returns null if no crash is pending.
         */
        fun getLastCrash(context: Context): String? =
            context
                .getSharedPreferences(CRASH_PREFS_NAME, Context.MODE_PRIVATE)
                .getString(CRASH_PREFS_KEY_LAST, null)

        /**
         * Clear the pending crash from SharedPreferences.
         */
        fun clearLastCrash(context: Context) {
            context
                .getSharedPreferences(CRASH_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(CRASH_PREFS_KEY_LAST)
                .apply()
        }

        /**
         * Get recent crash logs
         */
        fun getCrashLogs(context: Context): String =
            try {
                val file = File(context.filesDir, CRASH_LOG_FILE)
                if (file.exists()) file.readText() else "No crash logs"
            } catch (e: Exception) {
                "Error reading crash logs: ${e.message}"
            }

        /**
         * Clear crash logs
         */
        fun clearCrashLogs(context: Context) {
            try {
                File(context.filesDir, CRASH_LOG_FILE).delete()
                Log.i(TAG, "Crash logs cleared")
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing crash logs", e)
            }
        }

        fun recordPhase(
            phase: String,
            detail: String,
        ) {
            val runtime = Runtime.getRuntime()
            val entry =
                FlowCrashBreadcrumb(
                    timestampMs = System.currentTimeMillis(),
                    phase = phase.take(48),
                    detail = detail.take(240),
                    heapMb = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024,
                )
            synchronized(breadcrumbs) { breadcrumbs.record(entry, MAX_BREADCRUMBS) }
        }
    }

    override fun uncaughtException(
        thread: Thread,
        throwable: Throwable,
    ) {
        try {
            // Log to logcat
            Log.e(TAG, "=== UNCAUGHT EXCEPTION ===")
            Log.e(TAG, "Thread: ${thread.name} (id=${thread.id})")
            Log.e(TAG, "Exception: ${throwable.javaClass.simpleName}: ${throwable.message}")
            Log.e(TAG, getStackTraceString(throwable))

            // Save to SharedPreferences synchronously (commit) so the next launch
            // can detect the crash even if the app is killed before the file write completes.
            val stackTrace = getStackTraceString(throwable)
            val summary = buildCrashReport(thread, throwable, stackTrace)
            context
                .getSharedPreferences(CRASH_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(CRASH_PREFS_KEY_LAST, summary)
                .commit()

            // Save to file for later analysis
            saveCrashToFile(thread, throwable)
        } catch (e: Exception) {
            Log.e(TAG, "Error in crash handler", e)
        } finally {
            // Pass to default handler (which may show "app has stopped" dialog and kill process)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun saveCrashToFile(
        thread: Thread,
        throwable: Throwable,
    ) {
        try {
            val file = File(context.filesDir, CRASH_LOG_FILE)

            // Rotate if too large
            if (file.exists() && file.length() > MAX_CRASH_LOG_SIZE) {
                val backup = File(context.filesDir, "${CRASH_LOG_FILE}.old")
                file.renameTo(backup)
            }

            val stackTrace = getStackTraceString(throwable)
            val formattedCrash = buildCrashReport(thread, throwable, stackTrace)

            val crashReport =
                buildString {
                    appendLine(formattedCrash)
                    appendLine()
                }

            file.appendText(crashReport)
            Log.i(TAG, "Crash saved to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save crash to file", e)
        }
    }

    private fun getStackTraceString(throwable: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        return sw.toString()
    }

    private fun buildCrashReport(
        thread: Thread,
        throwable: Throwable,
        stackTrace: String,
    ): String {
        val snapshot =
            FlowCrashReportSnapshot(
                timestampMs = System.currentTimeMillis(),
                threadName = thread.name,
                threadId = thread.id,
                exceptionClass = throwable.javaClass.name,
                exceptionMessage = throwable.message,
                stackTrace = stackTrace,
                deviceInfo = buildDeviceInfo(),
                memoryInfo = buildMemoryInfo(),
                breadcrumbs = breadcrumbs.toList(),
            )
        return FlowCrashReportFormatter.build(snapshot)
    }

    private fun buildDeviceInfo(): String =
        buildString {
            appendLine("  Model: ${Build.MODEL}")
            appendLine("  Manufacturer: ${Build.MANUFACTURER}")
            appendLine("  Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("  Brand: ${Build.BRAND}")
            appendLine("  Device: ${Build.DEVICE}")
            try {
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                appendLine("  App Version: ${packageInfo.versionName} (${PackageInfoCompat.getLongVersionCode(packageInfo)})")
            } catch (e: Exception) {
                appendLine("  App Version: Unknown")
            }
        }

    private fun buildMemoryInfo(): String {
        val runtime = Runtime.getRuntime()
        val used = runtime.totalMemory() - runtime.freeMemory()
        return buildString {
            appendLine("  Used heap: ${used / 1024 / 1024} MB")
            appendLine("  Free heap: ${runtime.freeMemory() / 1024 / 1024} MB")
            appendLine("  Total heap: ${runtime.totalMemory() / 1024 / 1024} MB")
            appendLine("  Max heap: ${runtime.maxMemory() / 1024 / 1024} MB")
        }.trimEnd()
    }
}
