package io.github.aedev.flow.innertube.models

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class YouTubeClientTest {
    private val locale = YouTubeLocale(gl = "LB", hl = "en-US")

    private fun contextOf(client: YouTubeClient) = client.toContext(locale = locale, visitorData = null, dataSyncId = null)

    // Mirrors the configuration InnerTube.createClient installs; explicitNulls = false is the
    // property that keeps an unset userAgent out of the request body entirely.
    @OptIn(ExperimentalSerializationApi::class)
    private val innerTubeJson =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }

    @Test
    fun webContextMatchesDesktopClientShape() {
        val context = contextOf(YouTubeClient.WEB)

        assertEquals("https://www.youtube.com", context.client.originalUrl)
        assertEquals("DESKTOP", context.client.platform)
        assertEquals("LB", context.client.gl)
        assertEquals("en-US", context.client.hl)
    }

    @Test
    fun webClientsSendTheDeviceTimeZoneWithTheOffsetInForceAtTheTimeOfTheRequest() {
        val berlin = ZoneId.of("Europe/Berlin")
        val winter = Instant.parse("2026-01-15T12:00:00Z")
        val summer = Instant.parse("2026-07-15T12:00:00Z")

        listOf(YouTubeClient.WEB, YouTubeClient.MWEB).forEach { client ->
            val inWinter = client.toContext(locale, null, null, zone = berlin, now = winter).client
            val inSummer = client.toContext(locale, null, null, zone = berlin, now = summer).client

            assertEquals("Europe/Berlin", inWinter.timeZone)
            assertEquals(60, inWinter.utcOffsetMinutes)
            assertEquals(120, inSummer.utcOffsetMinutes)
        }
    }

    @Test
    fun aZoneBehindUtcSendsANegativeOffset() {
        val newYork = ZoneId.of("America/New_York")
        val context = YouTubeClient.WEB.toContext(locale, null, null, zone = newYork, now = Instant.parse("2026-01-15T12:00:00Z"))

        assertEquals(-300, context.client.utcOffsetMinutes)
        assertEquals("America/New_York", context.client.timeZone)
    }

    @Test
    fun otherClientsSendNoTimeZoneKeys() {
        listOf(YouTubeClient.WEB_REMIX, YouTubeClient.ANDROID, YouTubeClient.IOS, YouTubeClient.ANDROID_VR_1_65_10).forEach { client ->
            val encoded = innerTubeJson.encodeToJsonElement(contextOf(client)).jsonObject["client"]!!.jsonObject

            assertFalse(client.clientName, "utcOffsetMinutes" in encoded)
            assertFalse(client.clientName, "timeZone" in encoded)
        }
    }

    @Test
    fun mwebIsAnAttestedMobileWebClient() {
        val mweb = YouTubeClient.MWEB

        assertEquals("MWEB", mweb.clientName)
        assertEquals("2", mweb.clientId)
        assertEquals("MOBILE", contextOf(mweb).platform())
        assertTrue(mweb.useWebPoTokens)
        // The PoToken is bound to visitorData, so the request must stay unauthenticated.
        assertFalse(mweb.loginSupported)
    }

    @Test
    fun mwebEchoesItsUserAgentInsideTheContext() {
        assertEquals(YouTubeClient.USER_AGENT_MWEB, contextOf(YouTubeClient.MWEB).client.userAgent)
    }

    @Test
    fun everyOtherClientLeavesTheContextUserAgentUnset() {
        val others =
            listOf(
                YouTubeClient.WEB,
                YouTubeClient.WEB_REMIX,
                YouTubeClient.ANDROID,
                YouTubeClient.IOS,
                YouTubeClient.IPADOS,
                YouTubeClient.TVHTML5,
                YouTubeClient.ANDROID_VR_1_61_48,
                YouTubeClient.ANDROID_VR_1_65_10,
            )

        others.forEach { client ->
            assertNull(client.clientName, contextOf(client).client.userAgent)
        }
    }

    /**
     * The regression that matters for music: adding a field to the shared context model must not
     * add a key to the request bodies of the clients music resolves streams with.
     */
    @Test
    fun addingTheUserAgentFieldDoesNotAlterOtherClientsRequestBodies() {
        val musicClient = innerTubeJson.encodeToJsonElement(contextOf(YouTubeClient.WEB_REMIX)).jsonObject
        val client = musicClient["client"]!!.jsonObject

        assertFalse("userAgent" in client)
    }

    @Test
    fun mwebSerialisesTheUserAgentKey() {
        val encoded = innerTubeJson.encodeToJsonElement(contextOf(YouTubeClient.MWEB)).jsonObject
        val client = encoded["client"]!!.jsonObject

        assertTrue("userAgent" in client)
    }

    @Test
    fun theNewestAndroidVrBuildKeepsTheFamilyIdentity() {
        val vr = YouTubeClient.ANDROID_VR_1_65_10

        assertEquals("ANDROID_VR", vr.clientName)
        assertEquals("28", vr.clientId)
        assertEquals("1.65.10", vr.clientVersion)
        // A signature timestamp on ANDROID_VR makes YouTube treat the request as attested and stop
        // returning the clean direct URLs the fast path depends on.
        assertFalse(vr.useSignatureTimestamp)
        assertTrue(vr.clientVersion in vr.userAgent)
    }

    private fun Context.platform() = client.platform
}
