package io.github.aedev.flow.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FlowDiagnosticsNoiseTest {
    @Test
    fun `driver lines go and real errors stay`() {
        val logs =
            """
            11-04 19:19:59.834  7458  7516 E ev.flow.nightly: == MALI DEBUG ===eglp_winsys_populate_image_templates ==12288
            11-04 19:20:09.000  7458  7458 E EnhancedMusicPlayer: Player error: ERROR_CODE_IO_BAD_HTTP_STATUS (2004)
            """.trimIndent()

        assertThat(withoutDriverNoise(logs)).isEqualTo(
            "11-04 19:20:09.000  7458  7458 E EnhancedMusicPlayer: Player error: ERROR_CODE_IO_BAD_HTTP_STATUS (2004)",
        )
    }
}
