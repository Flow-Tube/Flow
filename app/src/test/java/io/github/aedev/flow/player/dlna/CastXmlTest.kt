package io.github.aedev.flow.player.dlna

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CastXmlTest {
    @Test
    fun `didl escapes the title and the uri`() {
        val didl = CastXml.didlLite("http://10.0.0.2:4000/s/a.mp4?x=1&y=2", CastItem("Tom & Jerry <live>"), "video/mp4")

        assertThat(didl).contains("<dc:title>Tom &amp; Jerry &lt;live&gt;</dc:title>")
        assertThat(didl).contains("""<res protocolInfo="http-get:*:video/mp4:*">http://10.0.0.2:4000/s/a.mp4?x=1&amp;y=2</res>""")
        assertThat(didl).contains("<upnp:class>object.item.videoItem</upnp:class>")
    }

    @Test
    fun `a song goes out as a music track with its artist, album, art and length`() {
        val item =
            CastItem(
                "Song",
                artist = "Artist",
                album = "Album",
                artUrl = "https://lh3.example/a=w1080",
                durationSeconds = 3725,
                music = true,
            )
        val didl = CastXml.didlLite("http://10.0.0.2:4000/s/b.m4a", item, "audio/mp4")

        assertThat(didl).contains("<upnp:class>object.item.audioItem.musicTrack</upnp:class>")
        assertThat(didl).contains("<upnp:artist>Artist</upnp:artist>")
        assertThat(didl).contains("<upnp:album>Album</upnp:album>")
        assertThat(didl).contains("<upnp:albumArtURI>https://lh3.example/a=w1080</upnp:albumArtURI>")
        assertThat(didl).contains("""<res protocolInfo="http-get:*:audio/mp4:*" duration="1:02:05.000">""")
    }

    @Test
    fun `a video carries no song fields`() {
        val didl = CastXml.didlLite("http://h/v.mp4", CastItem("Video", artist = "Channel"), "video/mp4")

        assertThat(didl).doesNotContain("upnp:artist")
        assertThat(didl).contains("""<res protocolInfo="http-get:*:video/mp4:*">""")
    }

    @Test
    fun `envelope wraps the action in its service`() {
        val soap = CastXml.soapEnvelope("Play", "urn:schemas-upnp-org:service:AVTransport:1", "<InstanceID>0</InstanceID>")

        assertThat(soap).contains("""<u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"><InstanceID>0</InstanceID></u:Play>""")
    }

    @Test
    fun `upnp times read as seconds`() {
        assertThat(CastXml.parseTimeToSeconds("0:03:25")).isEqualTo(205)
        assertThat(CastXml.parseTimeToSeconds("01:00:01.500")).isEqualTo(3601)
        assertThat(CastXml.parseTimeToSeconds("NOT_IMPLEMENTED")).isEqualTo(0)
    }

    @Test
    fun `position info keeps what the renderer answered`() {
        val info = CastXml.positionInfo("<RelTime>0:01:05</RelTime><TrackDuration>0:04:00</TrackDuration>")
        val missing = CastXml.positionInfo("<RelTime>0:00:09</RelTime>")

        assertThat(info).isEqualTo(CastPosition(position = 65, duration = 240))
        assertThat(missing).isEqualTo(CastPosition(position = 9, duration = null))
    }

    @Test
    fun `content type comes from the url's mime`() {
        assertThat(CastXml.guessContentType("https://x/videoplayback?mime=audio%2Fmp4&itag=140", "video/mp4")).isEqualTo("audio/mp4")
        assertThat(CastXml.guessContentType("https://x/videoplayback?itag=18", "audio/mp4")).isEqualTo("video/mp4")
        assertThat(CastXml.guessContentType("https://x/file", "video/webm")).isEqualTo("video/webm")
    }
}
