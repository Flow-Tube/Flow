package io.github.aedev.flow.data.contentfilter.aislist

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Base64

class AiListSourceTest {
    // keys/2026-11.pub.pem from Flow-Tube/AIlist, as a self-hoster would paste it.
    private val pipelinePem =
        """
        -----BEGIN PUBLIC KEY-----
        MCowBQYDK2VwAyEAxak9y4JaDG+UjKfl7cDwiaJs+7FjrOM+urMW1YfSuc0=
        -----END PUBLIC KEY-----
        """.trimIndent()

    @Test
    fun `the pipeline's PEM key and the raw key the app ships are the same key`() {
        val fromPem = AiListSource.decodeKey(pipelinePem)

        assertThat(fromPem).isEqualTo(AiListKeys.trusted.getValue("2026-11"))
        assertThat(AiListSource.decodeKey("xak9y4JaDG+UjKfl7cDwiaJs+7FjrOM+urMW1YfSuc0=")).isEqualTo(fromPem)
    }

    @Test
    fun `anything but a 32-byte Ed25519 key is refused`() {
        assertThat(AiListSource.decodeKey(Base64.getEncoder().encodeToString(ByteArray(31)))).isNull()
        assertThat(AiListSource.decodeKey("not base64!")).isNull()
    }

    @Test
    fun `only https URLs without a query are taken, and they end with a slash`() {
        assertThat(AiListSource.normalizeUrl(" https://lists.example.org/mirror ")).isEqualTo("https://lists.example.org/mirror/")
        assertThat(AiListSource.normalizeUrl("https://lists.example.org/")).isEqualTo("https://lists.example.org/")
        assertThat(AiListSource.normalizeUrl("http://lists.example.org/")).isNull()
        assertThat(AiListSource.normalizeUrl("https://lists.example.org/?x=1")).isNull()
        assertThat(AiListSource.normalizeUrl("lists.example.org")).isNull()
    }

    @Test
    fun `nothing set, or an invalid setting, means Flow's own mirror`() {
        assertThat(AiListSource.from(null, null).isDefault).isTrue()
        assertThat(AiListSource.from("http://insecure.example/", null).isDefault).isTrue()
        assertThat(AiListSource.from("https://lists.example.org/", "broken").isDefault).isTrue()
    }

    @Test
    fun `a mirror without a key trusts Flow's key, one with a key trusts only that key`() {
        val mirror = AiListSource.from("https://mirror.example/", null)
        val ownKey = AiListSource.from("https://mirror.example/", Base64.getEncoder().encodeToString(ByteArray(32) { 1 }))

        assertThat(mirror.keyFor("2026-11")).isEqualTo(AiListKeys.trusted.getValue("2026-11"))
        assertThat(mirror.keyFor("unknown")).isNull()
        assertThat(ownKey.keyFor("anything")).isEqualTo(ByteArray(32) { 1 })
        assertThat(ownKey.keyFor("2026-11")).isEqualTo(ByteArray(32) { 1 })
    }

    @Test
    fun `the same server with another key is another source`() {
        val a = AiListSource.from("https://lists.example.org/", Base64.getEncoder().encodeToString(ByteArray(32) { 1 }))
        val b = AiListSource.from("https://lists.example.org/", Base64.getEncoder().encodeToString(ByteArray(32) { 2 }))

        assertThat(a).isNotEqualTo(b)
        assertThat(a.host).isEqualTo("lists.example.org")
        assertThat(AiListSource.from("https://lists.example.org", null)).isEqualTo(AiListSource.from("https://lists.example.org/", null))
    }
}
