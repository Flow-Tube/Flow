package io.github.aedev.flow.innertube.models.response

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

/** Hand-written responses holding only the fields under test, in the shape YouTube sends them. */
class PlayerResponseSyntheticFieldsTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    private fun response(extra: String): PlayerResponse =
        json.decodeFromString("""{"responseContext":{},"playabilityStatus":{"status":"OK"}$extra}""")

    @Test
    fun `a live storyboard spec is read`() {
        val spec = "https://i.ytimg.com/sb/abc/storyboard_live_90_3x3_b2/M\$M.jpg?rs=x#159#90#3#3"
        val parsed = response(""","storyboards":{"playerLiveStoryboardSpecRenderer":{"spec":"$spec"}}""")

        assertThat(parsed.storyboards?.playerLiveStoryboardSpecRenderer?.spec).isEqualTo(spec)
        assertThat(parsed.storyboards?.playerStoryboardSpecRenderer).isNull()
    }
}
