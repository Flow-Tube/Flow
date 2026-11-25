package io.github.aedev.flow.innertube.pages

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class DecoratedPlayerBarKeysTest {
    private fun watch(overlay: String) = Json.parseToJsonElement("""{"playerOverlays":{"playerOverlayRenderer":$overlay}}""")

    @Test
    fun `a response without a campaign seek bar has no keys`() {
        assertThat(watch("""{"decoratedPlayerBarRenderer":null,"autoplay":{}}""").decoratedPlayerBarKeys()).isNull()
        assertThat(Json.parseToJsonElement("{}").decoratedPlayerBarKeys()).isNull()
    }

    @Test
    fun `a campaign seek bar lists its keys and its nested renderer's`() {
        val keys =
            watch(
                """{"decoratedPlayerBarRenderer":{"decoratedPlayerBarRenderer":{"playerBar":{},"playheadImage":{}},"trackingParams":"x"}}""",
            ).decoratedPlayerBarKeys()

        assertThat(keys)
            .containsExactly(
                "decoratedPlayerBarRenderer",
                "trackingParams",
                "decoratedPlayerBarRenderer.playerBar",
                "decoratedPlayerBarRenderer.playheadImage",
            ).inOrder()
    }
}
