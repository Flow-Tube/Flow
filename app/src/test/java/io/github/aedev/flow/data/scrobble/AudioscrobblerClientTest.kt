package io.github.aedev.flow.data.scrobble

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class AudioscrobblerClientTest {
    private val keys = AudioscrobblerKeys("key", "secret")
    private val sent = mutableListOf<Map<String, String>>()

    private fun client(
        reply: String,
        sessionAuth: MobileSessionAuth = MobileSessionAuth.PASSWORD,
    ) = AudioscrobblerClient("https://example.invalid/2.0/", sessionAuth = sessionAuth, http = {
        OkHttpClient
            .Builder()
            .addInterceptor { chain ->
                val form = chain.request().body as? FormBody
                sent +=
                    form?.let { (0 until it.size).associate { i -> it.name(i) to it.value(i) } }
                        ?: chain.request().url.let { url -> url.queryParameterNames.associateWith { url.queryParameter(it).orEmpty() } }
                Response
                    .Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(reply.toResponseBody("application/json".toMediaType()))
                    .build()
            }.build()
    })

    @Test
    fun `the signature follows the documented recipe`() {
        val params =
            mapOf("username" to "u", "method" to "auth.getMobileSession", "password" to "p", "api_key" to "xxx", "format" to "json")

        assertThat(audioscrobblerSignature(params, "s")).isEqualTo("f02a24fa543d12eeed6c58ed55b6c83a")
    }

    @Test
    fun `sign in returns the session and signs the request`() =
        runBlocking {
            val account =
                client(
                    """{"session":{"name":"Listener","key":"SK","subscriber":0}}""",
                ).signIn("listener", "pw", keys).getOrThrow()

            assertThat(account).isEqualTo(ScrobbleAccount("Listener", "SK"))
            val form = sent.single()
            assertThat(form["method"]).isEqualTo("auth.getMobileSession")
            assertThat(form["api_sig"]).isEqualTo(audioscrobblerSignature(form - "api_sig" - "format", "secret"))
        }

    @Test
    fun `the auth token is the md5 of the lower-cased name and the password's md5`() {
        assertThat(audioscrobblerAuthToken("listener", "pw")).isEqualTo("1d12e5ee336fb5afd24c404f2f7a6c1a")
        assertThat(audioscrobblerAuthToken("Listener", "pw")).isEqualTo(audioscrobblerAuthToken("listener", "pw"))
    }

    @Test
    fun `a token sign in sends the auth token and never the password`() =
        runBlocking {
            client(
                """{"session":{"name":"Listener","key":"SK","subscriber":0}}""",
                sessionAuth = MobileSessionAuth.AUTH_TOKEN,
            ).signIn("Listener", "pw", keys).getOrThrow()

            val form = sent.single()
            assertThat(form).doesNotContainKey("password")
            assertThat(form["authToken"]).isEqualTo("1d12e5ee336fb5afd24c404f2f7a6c1a")
            assertThat(form["username"]).isEqualTo("Listener")
            assertThat(form["api_sig"]).isEqualTo(audioscrobblerSignature(form - "api_sig" - "format", "secret"))
        }

    @Test
    fun `a password sign in sends the password and no token`() =
        runBlocking {
            client("""{"session":{"name":"Listener","key":"SK","subscriber":0}}""").signIn("listener", "pw", keys).getOrThrow()

            val form = sent.single()
            assertThat(form["password"]).isEqualTo("pw")
            assertThat(form).doesNotContainKey("authToken")
        }

    @Test
    fun `a rejected sign in carries the service's message`() =
        runBlocking {
            val result = client("""{"error":4,"message":"Invalid username or password"}""").signIn("u", "bad", keys)

            assertThat(result.exceptionOrNull()?.message).isEqualTo("Invalid username or password")
        }

    @Test
    fun `a batch sends indexed fields and an expired session signs out`() =
        runBlocking {
            val entries = listOf(ScrobbleEntry("A", "One", timestampSec = 10), ScrobbleEntry("B", "Two", "Record", 180, 20))
            val outcome = client("""{"error":9,"message":"Invalid session key"}""").scrobble(entries, ScrobbleAccount("u", "SK"), keys)

            assertThat(outcome).isEqualTo(SendOutcome.SignedOut)
            val form = sent.single()
            assertThat(form["track[1]"]).isEqualTo("Two")
            assertThat(form["album[1]"]).isEqualTo("Record")
            assertThat(form).doesNotContainKey("album[0]")
            assertThat(form["timestamp[0]"]).isEqualTo("10")
        }

    @Test
    fun `outages retry and other errors drop the batch`() {
        assertThat(outcomeOf(null)).isEqualTo(SendOutcome.Sent)
        assertThat(outcomeOf(11)).isEqualTo(SendOutcome.Retry)
        assertThat(outcomeOf(29)).isEqualTo(SendOutcome.Retry)
        assertThat(outcomeOf(6)).isEqualTo(SendOutcome.Sent)
    }

    @Test
    fun `a like becomes track love and an unlike track unlove`() =
        runBlocking {
            val client = client("{}")

            assertThat(client.setLoved(LoveEntry("A", "Song", loved = true), ScrobbleAccount("u", "SK"), keys)).isEqualTo(SendOutcome.Sent)
            client.setLoved(LoveEntry("A", "Song", loved = false), ScrobbleAccount("u", "SK"), keys)

            assertThat(sent.map { it["method"] }).containsExactly("track.love", "track.unlove").inOrder()
            assertThat(sent.first()["sk"]).isEqualTo("SK")
        }

    @Test
    fun `top artists are read by name in play order`() =
        runBlocking {
            val reply = """{"topartists":{"artist":[{"name":"First","playcount":"90"},{"name":"Second","playcount":"40"}]}}"""

            val artists = client(reply).topArtists("listener", keys, limit = 20).getOrThrow()

            assertThat(artists).containsExactly("First", "Second").inOrder()
            assertThat(sent.single()["method"]).isEqualTo("user.getTopArtists")
            assertThat(sent.single()["period"]).isEqualTo("12month")
        }

    @Test
    fun `similar tracks are read as artist and title pairs`() =
        runBlocking {
            val reply = """{"similartracks":{"track":[{"name":"Near","artist":{"name":"Other"}},{"name":"","artist":{"name":"Skip"}}]}}"""

            val similar = client(reply).similarTracks("Artist", "Song", keys, limit = 15).getOrThrow()

            assertThat(similar).containsExactly("Other" to "Near")
            assertThat(sent.single()["method"]).isEqualTo("track.getSimilar")
            assertThat(sent.single()["autocorrect"]).isEqualTo("1")
        }
}
