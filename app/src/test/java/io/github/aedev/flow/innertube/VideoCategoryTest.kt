package io.github.aedev.flow.innertube

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class VideoCategoryTest {
    private fun category(json: String) = Json.parseToJsonElement(json).jsonObject.toVideoCategory()

    @Test
    fun `the masked answer carries only the category`() {
        assertThat(category("""{"microformat":{"playerMicroformatRenderer":{"category":"Music"}}}""")).isEqualTo("Music")
    }

    @Test
    fun `a whole player response reads the same`() {
        val whole =
            """
            {"responseContext":{},"playabilityStatus":{"status":"UNPLAYABLE"},
             "microformat":{"playerMicroformatRenderer":{"title":{"simpleText":"t"},"category":"Gaming"}}}
            """.trimIndent()

        assertThat(category(whole)).isEqualTo("Gaming")
    }

    @Test
    fun `no category or a blank one is none`() {
        assertThat(category("""{}""")).isNull()
        assertThat(category("""{"microformat":{"playerMicroformatRenderer":{"category":" "}}}""")).isNull()
    }
}
