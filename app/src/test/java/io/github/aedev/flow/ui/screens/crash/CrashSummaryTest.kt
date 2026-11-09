package io.github.aedev.flow.ui.screens.crash

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.utils.FlowCrashReportFormatter
import io.github.aedev.flow.utils.FlowCrashReportSnapshot
import org.junit.Test
import java.net.URLDecoder
import java.time.LocalDateTime

class CrashSummaryTest {
    private val report =
        FlowCrashReportFormatter.build(
            FlowCrashReportSnapshot(
                timestampMs = 0,
                threadName = "main",
                threadId = 2,
                exceptionClass = "java.lang.IllegalArgumentException",
                exceptionMessage = "Padding must be non-negative",
                stackTrace =
                    """
                    java.lang.IllegalArgumentException: Padding must be non-negative
                    	at androidx.compose.foundation.layout.PaddingKt.padding(Padding.kt:88)
                    	at io.github.aedev.flow.ui.screens.player.PlayerBottomBarKt.PlayerBottomBar(PlayerBottomBar.kt:212)
                    """.trimIndent(),
                deviceInfo = "  Model: 23049PCD8G\n  Manufacturer: Xiaomi\n  Android: 16 (SDK 36)\n  App Version: 2.2.5 (225)",
                memoryInfo = "  Used heap: 10 MB",
                breadcrumbs = emptyList(),
            ),
        )

    @Test
    fun `the summary points at the first frame in Flow's own code`() {
        val summary = parseCrashSummary(report)

        assertThat(summary.exception).isEqualTo("IllegalArgumentException")
        assertThat(summary.message).isEqualTo("Padding must be non-negative")
        assertThat(summary.location).isEqualTo("PlayerBottomBar.kt:212")
        assertThat(summary.appVersion).isEqualTo("2.2.5 (225)")
        assertThat(summary.device).isEqualTo("Xiaomi 23049PCD8G")
        assertThat(summary.time).isInstanceOf(LocalDateTime::class.java)
    }

    @Test
    fun `the issue link fills the bug form fields by id`() {
        val url = parseCrashSummary(report).issueUrl()

        assertThat(
            url,
        ).startsWith("https://github.com/Flow-Tube/Flow/issues/new?template=bug_report.yml&title=%5BBug%5D%3A%20IllegalArgumentException")
        assertThat(url).contains("&app-version=2.2.5&")
        assertThat(url).contains("&android-version=16%20%28SDK%2036%29&")
        assertThat(url).contains("&device=Xiaomi%2023049PCD8G&")
        assertThat(url).contains("&logs=IllegalArgumentException%3A%20Padding")
    }

    @Test
    fun `the link answers every required text field, so a crash can be sent without a questionnaire (1189)`() {
        val url = parseCrashSummary(report).issueUrl()

        assertThat(url).contains("&steps=Not%20known.")
        assertThat(url).contains("&expected=Flow%20keeps%20running.")
        assertThat(url).contains("&actual=Flow%20crashed%20with%20IllegalArgumentException%3A%20Padding%20must%20be%20non-negative&")
    }

    @Test
    fun `an unreadable report still gives a usable summary`() {
        val summary = parseCrashSummary("something went wrong")

        assertThat(summary.exception).isEqualTo("Crash")
        assertThat(summary.location).isNull()
    }

    @Test
    fun `a wrapped cause names the title, as with a saved state too large to send (1338)`() {
        val wrapped =
            FlowCrashReportFormatter.build(
                FlowCrashReportSnapshot(
                    timestampMs = 0,
                    threadName = "main",
                    threadId = 2,
                    exceptionClass = "java.lang.RuntimeException",
                    exceptionMessage = "android.os.TransactionTooLargeException: data parcel size 1052744 bytes",
                    stackTrace =
                        """
                        java.lang.RuntimeException: android.os.TransactionTooLargeException: data parcel size 1052744 bytes
                        	at android.app.servertransaction.PendingTransactionActions${'$'}StopInfo.run(PendingTransactionActions.java:146)
                        """.trimIndent(),
                    deviceInfo = "  Model: Pixel 8\n  Manufacturer: Google",
                    memoryInfo = "",
                    breadcrumbs = emptyList(),
                ),
            )
        val summary = parseCrashSummary(wrapped)

        assertThat(summary.exception).isEqualTo("RuntimeException")
        assertThat(summary.headline).isEqualTo("TransactionTooLargeException")
        assertThat(
            summary.issueUrl(),
        ).contains("title=%5BBug%5D%3A%20TransactionTooLargeException%20in%20PendingTransactionActions.java%3A146")
    }

    @Test
    fun `a crash Android wraps twice is named and logged by its innermost cause (1871)`() {
        val wrapped =
            FlowCrashReportFormatter.build(
                FlowCrashReportSnapshot(
                    timestampMs = 0,
                    threadName = "main",
                    threadId = 2,
                    exceptionClass = "java.lang.RuntimeException",
                    exceptionMessage = "java.lang.reflect.InvocationTargetException",
                    stackTrace =
                        """
                        java.lang.RuntimeException: java.lang.reflect.InvocationTargetException
                        	at com.android.internal.os.RuntimeInit${'$'}MethodAndArgsCaller.run(RuntimeInit.java:588)
                        	at com.android.internal.os.ZygoteInit.main(ZygoteInit.java:1103)
                        Caused by: java.lang.reflect.InvocationTargetException
                        	at java.lang.reflect.Method.invoke(Native Method)
                        	at com.android.internal.os.RuntimeInit${'$'}MethodAndArgsCaller.run(RuntimeInit.java:578)
                        	... 1 more
                        Caused by: androidx.compose.material3.internal.AnchoredDraggableUninitializedException: offset NaN, anchors {Hidden=0.0}
                        	at androidx.compose.material3.AppBarKt${'$'}${'$'}ExternalSyntheticLambda12.invoke(r8-map-id-6b62:135)
                        	at androidx.compose.material3.AppBarKt${'$'}${'$'}ExternalSyntheticLambda12.invoke(r8-map-id-6b62:114)
                        	at androidx.compose.ui.layout.Placeable${'$'}PlacementScope.place(r8-map-id-6b62:20)
                        	at io.github.aedev.flow.ui.components.videoplayer.sheet.PlaylistQueueItemKt.QueueRow(r8-map-id-6b62:79)
                        	at androidx.compose.ui.node.LayoutNode.place(r8-map-id-6b62:40)
                        	... 30 more
                        """.trimIndent(),
                    deviceInfo = "  Model: SM-A137F\n  Manufacturer: samsung",
                    memoryInfo = "",
                    breadcrumbs = emptyList(),
                ),
            )
        val summary = parseCrashSummary(wrapped)
        val logs = URLDecoder.decode(summary.issueUrl().substringAfter("&logs="), Charsets.UTF_8.name())

        assertThat(summary.headline).isEqualTo("AnchoredDraggableUninitializedException")
        assertThat(summary.location).isEqualTo("r8-map-id-6b62:79")
        assertThat(logs).contains("Caused by: androidx.compose.material3.internal.AnchoredDraggableUninitializedException: offset NaN")
        assertThat(logs).contains("PlaylistQueueItemKt.QueueRow")
        assertThat(logs).doesNotContain("Method.invoke")
    }

    @Test
    fun `a long outer trace never pushes the cause out of the logs field`() {
        val outer = (1..40).joinToString("\n") { "\tat some.Outer.frame$it(Outer.kt:$it)" }
        val report =
            FlowCrashReportFormatter.build(
                FlowCrashReportSnapshot(
                    timestampMs = 0,
                    threadName = "main",
                    threadId = 2,
                    exceptionClass = "java.lang.RuntimeException",
                    exceptionMessage = "x".repeat(2_000),
                    stackTrace =
                        "java.lang.RuntimeException: boom\n$outer\n" +
                            "Caused by: java.io.IOException: disk\n\tat some.Inner.read(Inner.kt:1)",
                    deviceInfo = "",
                    memoryInfo = "",
                    breadcrumbs = emptyList(),
                ),
            )
        val logs = URLDecoder.decode(parseCrashSummary(report).issueUrl().substringAfter("&logs="), Charsets.UTF_8.name())

        assertThat(logs.length).isAtMost(1_500)
        assertThat(logs).endsWith("Caused by: java.io.IOException: disk\nat some.Inner.read(Inner.kt:1)")
    }

    @Test
    fun `a plain message keeps the exception's own name`() {
        assertThat(parseCrashSummary(report).headline).isEqualTo("IllegalArgumentException")
    }
}
