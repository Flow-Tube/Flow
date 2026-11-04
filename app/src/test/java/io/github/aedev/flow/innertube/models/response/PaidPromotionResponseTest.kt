package io.github.aedev.flow.innertube.models.response

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class PaidPromotionResponseTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun label(raw: String) = json.decodeFromString(PaidPromotionResponse.serializer(), raw).label()

    @Test
    fun `a video with paid promotion reads its wording and length`() {
        val raw =
            """
            {"playabilityStatus":{"status":"OK"},
             "miniPlayerByline":{"miniPlayerBylineRenderer":{
               "text":{"runs":[{"text":"Includes paid promotion"}]},"durationMs":"10000"}}}
            """.trimIndent()

        assertThat(label(raw)).isEqualTo(PaidPromotionLabel("Includes paid promotion", 10_000L))
    }

    @Test
    fun `a video without the field has no label`() {
        assertThat(label("""{"playabilityStatus":{"status":"OK"}}""")).isNull()
    }

    @Test
    fun `a refused request has no label`() {
        val raw =
            """
            {"playabilityStatus":{"status":"LOGIN_REQUIRED"},
             "miniPlayerByline":{"miniPlayerBylineRenderer":{"text":{"runs":[{"text":"x"}]}}}}
            """.trimIndent()

        assertThat(label(raw)).isNull()
    }

    @Test
    fun `missing wording and length fall back`() {
        val raw = """{"playabilityStatus":{"status":"OK"},"miniPlayerByline":{"miniPlayerBylineRenderer":{}}}"""

        assertThat(label(raw)).isEqualTo(PaidPromotionLabel(null, 10_000L))
    }
}
