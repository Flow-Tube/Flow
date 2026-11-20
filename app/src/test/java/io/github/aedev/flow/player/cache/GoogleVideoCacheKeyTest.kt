package io.github.aedev.flow.player.cache

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GoogleVideoCacheKeyTest {
    private fun url(
        host: String = "rr3---sn-abc.googlevideo.com",
        expire: String = "1700000000",
        sig: String = "AAA",
        extra: String = "",
    ) = "https://$host/videoplayback?expire=$expire&ei=x&ip=1.2.3.4&id=o-ABC&itag=137&source=youtube" +
        "&mime=video%2Fmp4&clen=12345&lmt=1699999999123456&sig=$sig$extra"

    @Test
    fun `two extractions of the same stream share a key`() {
        val first = googleVideoStreamKey(url(expire = "1700000000", sig = "AAA"))
        val second = googleVideoStreamKey(url(host = "rr5---sn-xyz.googlevideo.com", expire = "1700009999", sig = "BBB"))

        assertThat(first).isEqualTo("googlevideo:137:1699999999123456:12345:")
        assertThat(second).isEqualTo(first)
    }

    @Test
    fun `audio variants with different xtags stay apart`() {
        val original = googleVideoStreamKey(url(extra = "&xtags=acont%3Doriginal"))
        val dubbed = googleVideoStreamKey(url(extra = "&xtags=acont%3Ddubbed%3Alang%3Des"))

        assertThat(original).isNotEqualTo(dubbed)
    }

    @Test
    fun `streams without an encode identity keep their url`() {
        assertThat(googleVideoStreamKey("https://rr3---sn-abc.googlevideo.com/videoplayback?itag=137&clen=1")).isNull()
        assertThat(googleVideoStreamKey("https://manifest.googlevideo.com/api/manifest/hls_playlist/id/x")).isNull()
        assertThat(googleVideoStreamKey("https://example.com/videoplayback?itag=137&lmt=1&clen=1")).isNull()
    }
}
