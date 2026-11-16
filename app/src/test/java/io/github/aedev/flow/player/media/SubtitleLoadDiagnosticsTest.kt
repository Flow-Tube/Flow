package io.github.aedev.flow.player.media

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class SubtitleLoadDiagnosticsTest {
    private val now = 1_800_000_000L

    private val translatedUrl =
        "https://www.youtube.com/api/timedtext?v=WJ8clVdaRKA&ei=SECRETEI&caps=asr&opi=112496729" +
            "&exp=xpe&xoaf=5&hl=en&ip=0.0.0.0&ipbits=0&expire=${now + 3_600}" +
            "&sparams=ip%2Cipbits%2Cexpire%2Cv%2Cei%2Ccaps%2Copi%2Cexp%2Cxoaf" +
            "&signature=SECRETSIGNATURE&key=yt8&kind=asr&lang=en&c=ANDROID_VR&fmt=srv3&tlang=pt"

    @Test
    fun `the line carries what tells the failures apart`() {
        val line = SubtitleLoadDiagnostics.describe(translatedUrl, IOException("boom"), now)

        assertThat(line).contains("host=www.youtube.com")
        assertThat(line).contains("c=ANDROID_VR")
        assertThat(line).contains("fmt=srv3")
        assertThat(line).contains("tlang=yes")
        assertThat(line).contains("pot=no")
        assertThat(line).contains("expiresIn=3600s")
        assertThat(line).contains("status=none")
        assertThat(line).contains("error=IOException")
    }

    @Test
    fun `nothing that could replay the request is logged`() {
        val url = "$translatedUrl&pot=SECRETPOT"

        val line = SubtitleLoadDiagnostics.describe(url, IOException(url), now)

        assertThat(line).contains("pot=yes")
        listOf("SECRET", "WJ8clVdaRKA", "/api/timedtext", "https://", "signature", "sparams", "yt8", "boom").forEach {
            assertThat(line).doesNotContain(it)
        }
    }

    @Test
    fun `a duplicated fmt is reported in the order the endpoint reads it`() {
        val line = SubtitleLoadDiagnostics.describe("$translatedUrl&fmt=vtt", IOException(), now)

        assertThat(line).contains("fmt=srv3,vtt")
    }

    @Test
    fun `an expired caption url says how long ago`() {
        val line = SubtitleLoadDiagnostics.describe(translatedUrl, IOException(), now + 3_700)

        assertThat(line).contains("expiresIn=-100s")
    }

    @Test
    fun `the underlying cause class is named`() {
        val line = SubtitleLoadDiagnostics.describe(translatedUrl, IOException("wrapped", UnknownHostException("host")), now)

        assertThat(line).contains("cause=UnknownHostException")
    }

    @Test
    fun `an offline sidecar is reported as local with no query facts`() {
        val line = SubtitleLoadDiagnostics.describe("file:///data/user/0/app/files/offline_subtitles/id/en.vtt", IOException(), now)

        assertThat(line).contains("host=local")
        assertThat(line).contains("c=none")
        assertThat(line).contains("fmt=none")
        assertThat(line).contains("expire=absent")
        assertThat(line).doesNotContain("offline_subtitles")
    }

    @Test
    fun `a translation says which track it came from`() {
        assertThat(SubtitleLoadDiagnostics.describe(translatedUrl, IOException(), now)).contains("source=asr")
        assertThat(SubtitleLoadDiagnostics.describe(translatedUrl.replace("&kind=asr", ""), IOException(), now))
            .contains("source=authored")
    }

    @Test
    fun `a loaded translation is logged with its shape and size only`() {
        assertThat(SubtitleLoadDiagnostics.isTranslated(translatedUrl)).isTrue()
        assertThat(SubtitleLoadDiagnostics.isTranslated("https://www.youtube.com/api/timedtext?v=x&lang=en&fmt=vtt")).isFalse()

        val line = SubtitleLoadDiagnostics.describeTranslatedLoad(translatedUrl, bytes = 4_096)

        assertThat(line).isEqualTo("fmt=srv3 source=asr bytes=4096")
    }
}
