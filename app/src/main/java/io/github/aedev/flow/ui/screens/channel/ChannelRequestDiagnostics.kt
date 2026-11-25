package io.github.aedev.flow.ui.screens.channel

import android.os.Build
import io.github.aedev.flow.BuildConfig
import io.github.aedev.flow.innertube.YouTube

internal fun buildChannelRequestErrorLog(
    operation: String,
    channelId: String,
    error: Throwable,
    query: String? = null,
): String {
    val locale = YouTube.locale
    return buildString {
        appendLine("FLOW CHANNEL REQUEST ERROR")
        appendLine("Operation: $operation")
        appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("WEB client: ${YouTube.webClientVersionInUse()}")
        appendLine("Locale: hl=${locale.hl}, gl=${locale.gl}")
        appendLine("Visitor data present: ${!YouTube.visitorData.isNullOrBlank()}")
        appendLine("Channel: $channelId")
        query?.let { appendLine("Query: $it") }
        appendLine()
        append(error.stackTraceToString())
    }
}
