package io.github.aedev.flow.player.stream

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CaptionUrlFallbackTest {
    private val signed =
        "https://www.youtube.com/api/timedtext?v=7OA0ZdLCV0s&ei=EI&caps=asr&opi=112496729&xoaf=5&hl=en" +
            "&ip=0.0.0.0&ipbits=0&expire=1800000000&sparams=ip%2Cipbits%2Cexpire%2Cv%2Cei%2Ccaps%2Copi%2Cxoaf" +
            "&signature=SIG&key=yt8"
    private val gemini = "$signed&kind=asr&lang=en&variant=gemini&fmt=vtt"

    @Test
    fun `a refused gemini track falls back to the classic auto track`() {
        assertThat(CaptionUrlFallback.alternateFor(gemini)).isEqualTo("$signed&kind=asr&lang=en&fmt=vtt")
    }

    @Test
    fun `every signed parameter is kept in order`() {
        val alternate = CaptionUrlFallback.alternateFor(gemini)!!

        assertThat(alternate).startsWith(signed)
        assertThat(alternate).doesNotContain("variant")
    }

    @Test
    fun `a variant anywhere in the query is dropped`() {
        val url = "${signed.replace("&signature", "&variant=gemini&signature")}&kind=asr&lang=en&fmt=vtt"

        assertThat(CaptionUrlFallback.alternateFor(url)).isEqualTo("$signed&kind=asr&lang=en&fmt=vtt")
    }

    @Test
    fun `tracks with nothing to fall back to get no alternate`() {
        assertThat(CaptionUrlFallback.alternateFor("$signed&kind=asr&lang=en&fmt=vtt")).isNull()
        assertThat(CaptionUrlFallback.alternateFor("$signed&lang=en&variant=gemini&fmt=srv3")).isNull()
        assertThat(CaptionUrlFallback.alternateFor("$gemini&tlang=pt")).isNull()
        assertThat(CaptionUrlFallback.alternateFor("https://www.youtube.com/api/timedtext")).isNull()
    }

    @Test
    fun `only statuses a retry cannot change count as refusals`() {
        listOf(400, 403, 404, 410).forEach { assertThat(CaptionUrlFallback.isRefusal(it)).isTrue() }
        listOf(null, 200, 429, 500, 503).forEach { assertThat(CaptionUrlFallback.isRefusal(it)).isFalse() }
    }

    @Test
    fun `forbidden and missing tracks read as unavailable`() {
        listOf(403, 404, 410).forEach { assertThat(CaptionUrlFallback.isUnavailable(it)).isTrue() }
        listOf(null, 400, 429, 500).forEach { assertThat(CaptionUrlFallback.isUnavailable(it)).isFalse() }
    }
}
