package io.github.aedev.flow.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.ArrayDeque

class FlowCrashReportFormatterTest {
    @Test
    fun `oom report separates input state updates and final output`() {
        val report =
            FlowCrashReportFormatter.build(
                FlowCrashReportSnapshot(
                    timestampMs = 1_800_000_000_000L,
                    threadName = "main",
                    threadId = 1L,
                    exceptionClass = OutOfMemoryError::class.java.name,
                    exceptionMessage = "Failed to allocate",
                    stackTrace =
                        """
                        java.lang.OutOfMemoryError: Failed to allocate
                            at io.github.aedev.flow.player.PlayerCacheManager.updateCache(PlayerCacheManager.kt:42)
                            at io.github.aedev.flow.service.VideoPlayerService.onStartCommand(VideoPlayerService.kt:121)
                            at android.app.ActivityThread.handleServiceArgs(ActivityThread.java:5000)
                        """.trimIndent(),
                    deviceInfo = "Model: Pixel Test",
                    memoryInfo = "Max heap: 256 MB",
                    breadcrumbs =
                        listOf(
                            FlowCrashBreadcrumb(1_799_999_990_000L, "activity", "onStop pip=false"),
                            FlowCrashBreadcrumb(1_799_999_991_000L, "background-handoff", "handleBackgroundPlaybackOnStop"),
                            FlowCrashBreadcrumb(1_799_999_992_000L, "video-service", "onStartCommand action=null startId=1"),
                        ),
                ),
            )

        assertThat(report).contains("Crash Input")
        assertThat(report).contains("OutOfMemoryError: true")
        assertThat(report).contains("State/Cache Updates Before Crash")
        assertThat(report).contains("background-handoff: handleBackgroundPlaybackOnStop")
        assertThat(report).contains("Final Output")
        assertThat(report).contains("Top Stack Frames (same build):")
        assertThat(report).contains("PlayerCacheManager.updateCache")
    }

    @Test
    fun `top stack frames keeps only actionable frame lines`() {
        val frames =
            FlowCrashReportFormatter.topStackFrames(
                """
                java.lang.IllegalStateException: boom
                    at first.Frame.call(First.kt:1)
                    at second.Frame.call(Second.kt:2)
                Caused by: java.io.IOException: nope
                    at third.Frame.call(Third.kt:3)
                """.trimIndent(),
                limit = 3,
            )

        assertThat(frames)
            .containsExactly(
                "at first.Frame.call(First.kt:1)",
                "at second.Frame.call(Second.kt:2)",
                "Caused by: java.io.IOException: nope",
            ).inOrder()
    }

    @Test
    fun `folded breadcrumbs show their count and heap`() {
        val report =
            FlowCrashReportFormatter.build(
                FlowCrashReportSnapshot(
                    timestampMs = 1_800_000_000_000L,
                    threadName = "main",
                    threadId = 1L,
                    exceptionClass = OutOfMemoryError::class.java.name,
                    exceptionMessage = null,
                    stackTrace = "java.lang.OutOfMemoryError",
                    deviceInfo = "",
                    memoryInfo = "",
                    breadcrumbs =
                        listOf(
                            FlowCrashBreadcrumb(1_799_999_990_000L, "video-service", "onStartCommand action=null", heapMb = 212, count = 5),
                            FlowCrashBreadcrumb(1_799_999_991_000L, "video-item", "load abc audioOnly=false"),
                        ),
                ),
            )

        assertThat(report).contains("video-service: onStartCommand action=null ×5 heap=212MB")
        assertThat(report).contains("video-item: load abc audioOnly=false\n")
    }

    @Test
    fun `a repeated breadcrumb folds into one and the ring keeps its size`() {
        val ring = ArrayDeque<FlowCrashBreadcrumb>()
        ring.record(FlowCrashBreadcrumb(1, "activity", "onResume"), max = 3)
        repeat(5) { ring.record(FlowCrashBreadcrumb(2L + it, "video-service", "onStartCommand action=null", heapMb = 100L + it), max = 3) }
        ring.record(FlowCrashBreadcrumb(10, "video-item", "load a"), max = 3)
        ring.record(FlowCrashBreadcrumb(11, "video-item", "load b"), max = 3)

        assertThat(ring.map { it.detail }).containsExactly("onStartCommand action=null", "load a", "load b").inOrder()
        assertThat(ring.first.count).isEqualTo(5)
        assertThat(ring.first.heapMb).isEqualTo(104L)
        assertThat(ring.first.timestampMs).isEqualTo(6L)
    }
}
