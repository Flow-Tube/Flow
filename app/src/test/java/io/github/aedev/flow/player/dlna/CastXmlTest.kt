package io.github.aedev.flow.player.dlna

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CastXmlTest {
    @Test
    fun `didl escapes the title and the uri`() {
        val didl = CastXml.didlLite("http://10.0.0.2:4000/s/a.mp4?x=1&y=2", "Tom & Jerry <live>", "video/mp4")

        assertThat(didl).contains("<dc:title>Tom &amp; Jerry &lt;live&gt;</dc:title>")
        assertThat(didl).contains("""<res protocolInfo="http-get:*:video/mp4:*">http://10.0.0.2:4000/s/a.mp4?x=1&amp;y=2</res>""")
        assertThat(didl).contains("<upnp:class>object.item.videoItem</upnp:class>")
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
