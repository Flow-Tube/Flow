package io.github.aedev.flow.data.scrobble

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test

class WebhookClientTest {
    private val entry =
        ScrobbleEntry(
            artist = "Artist",
            title = "Song",
            album = "Album",
            durationSec = 200,
            timestampSec = 1_700_000_000,
            videoId = "abc123",
            thumbnailUrl = "https://lh3.googleusercontent.com/art=w1080",
        )

    private fun JsonObject.obj(key: String) = getValue(key).jsonObject

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    @Test
    fun `a scrobble follows Web Scrobbler's envelope`() {
        val event = webhookEventJson(WebhookClient.SCROBBLE, 1_700_000_300_000, entry)
        val song = event.obj("data").obj("song")
        val parsed = song.obj("parsed")

        assertThat(event.text("eventName")).isEqualTo("scrobble")
        assertThat(event.getValue("time").jsonPrimitive.long).isEqualTo(1_700_000_300_000)
        assertThat(event.obj("data")).doesNotContainKey("isLoved")
        assertThat(parsed.text("track")).isEqualTo("Song")
        assertThat(parsed.text("artist")).isEqualTo("Artist")
        assertThat(parsed.text("album")).isEqualTo("Album")
        assertThat(parsed.getValue("duration").jsonPrimitive.long).isEqualTo(200)
        assertThat(parsed.text("uniqueID")).isEqualTo("abc123")
        assertThat(parsed.text("originUrl")).isEqualTo("https://music.youtube.com/watch?v=abc123")
        assertThat(parsed.text("trackArt")).isEqualTo("https://lh3.googleusercontent.com/art=w1080")
        assertThat(parsed.getValue("isPodcast").jsonPrimitive.boolean).isFalse()
        assertThat(parsed.getValue("albumArtist")).isEqualTo(JsonNull)
        assertThat(song.obj("processed").text("track")).isEqualTo("Song")
        val metadata = song.obj("metadata")
        assertThat(metadata.getValue("startTimestamp").jsonPrimitive.long).isEqualTo(1_700_000_000)
        assertThat(song.text("connectorLabel")).isEqualTo("Flow")
    }

    @Test
    fun `a file on the device has no YouTube link or empty fields`() {
        val local = entry.copy(album = "", durationSec = 0, fromYouTube = false, videoId = "local_9", thumbnailUrl = "")
        val parsed = webhookEventJson(WebhookClient.NOW_PLAYING, 0, local).obj("data").obj("song").obj("parsed")

        assertThat(parsed.getValue("uniqueID")).isEqualTo(JsonNull)
        assertThat(parsed.getValue("originUrl")).isEqualTo(JsonNull)
        assertThat(parsed.getValue("trackArt")).isEqualTo(JsonNull)
        assertThat(parsed.getValue("album")).isEqualTo(JsonNull)
        assertThat(parsed.getValue("duration")).isEqualTo(JsonNull)
    }

    @Test
    fun `a love carries isLoved next to the song`() {
        val event = webhookEventJson(WebhookClient.LOVED, 5, entry, isLoved = false)

        assertThat(event.text("eventName")).isEqualTo("loved")
        val data = event.obj("data")
        assertThat(data.getValue("isLoved").jsonPrimitive.boolean).isFalse()
    }

    @Test
    fun `rate limits and server errors retry, other answers are done`() {
        assertThat(webhookOutcome(200)).isEqualTo(SendOutcome.Sent)
        assertThat(webhookOutcome(204)).isEqualTo(SendOutcome.Sent)
        assertThat(webhookOutcome(429)).isEqualTo(SendOutcome.Retry)
        assertThat(webhookOutcome(500)).isEqualTo(SendOutcome.Retry)
        assertThat(webhookOutcome(503)).isEqualTo(SendOutcome.Retry)
        assertThat(webhookOutcome(400)).isEqualTo(SendOutcome.Sent)
        assertThat(webhookOutcome(401)).isEqualTo(SendOutcome.Sent)
        assertThat(webhookOutcome(404)).isEqualTo(SendOutcome.Sent)
    }

    @Test
    fun `only http and https addresses are accepted`() {
        assertThat(isWebhookAddress("http://192.168.1.5:8080/scrobble")).isTrue()
        assertThat(isWebhookAddress(" https://example.com/hook?token=x ")).isTrue()
        assertThat(isWebhookAddress("ftp://example.com")).isFalse()
        assertThat(isWebhookAddress("example.com")).isFalse()
        assertThat(isWebhookAddress("")).isFalse()
    }

    @Test
    fun `listens queued before webhooks existed still decode`() {
        val json = Json { ignoreUnknownKeys = true }
        val stored = """[{"artist":"A","title":"T","album":"R","durationSec":180,"timestampSec":10,"fromYouTube":true}]"""
        val loves = """[{"artist":"A","title":"T","loved":true}]"""

        val entry = json.decodeFromString<List<ScrobbleEntry>>(stored).single()
        val love = json.decodeFromString<List<LoveEntry>>(loves).single()

        assertThat(entry).isEqualTo(ScrobbleEntry("A", "T", "R", 180, 10))
        assertThat(entry.videoId).isEmpty()
        assertThat(entry.thumbnailUrl).isEmpty()
        assertThat(love).isEqualTo(LoveEntry("A", "T", loved = true))
    }
}
