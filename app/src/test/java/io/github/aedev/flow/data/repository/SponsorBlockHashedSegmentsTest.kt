package io.github.aedev.flow.data.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertThrows
import org.junit.Test

class SponsorBlockHashedSegmentsTest {
    @Test
    fun `the lookup sends only a prefix of the id's hash`() {
        val url = SponsorBlockRepository.nonMusicSegmentsUrl("YQHsXMglC9A")

        assertThat(url.pathSegments.last()).isEqualTo("0c7c")
        assertThat(url.toString()).doesNotContain("YQHsXMglC9A")
        assertThat(url.queryParameter("categories")).isEqualTo("""["music_offtopic"]""")
        assertThat(url.queryParameter("actionTypes")).isEqualTo("""["skip"]""")
    }

    @Test
    fun `only the playing video's segments are read from the shared prefix`() {
        val json =
            """
            [
              {"videoID":"other","hash":"0c7c1","segments":[
                {"category":"music_offtopic","actionType":"skip","segment":[1,2],"videoDuration":10}]},
              {"videoID":"YQHsXMglC9A","hash":"0c7c2","extra":true,"segments":[
                {"category":"music_offtopic","actionType":"skip","segment":[0,74.9],"videoDuration":366.861,"UUID":"a"},
                {"category":"music_offtopic","actionType":"mute","segment":[80,81]},
                {"category":"sponsor","actionType":"skip","segment":[90,91]},
                {"category":"music_offtopic","actionType":"skip","segment":[100,110]}]}
            ]
            """.trimIndent()

        assertThat(SponsorBlockRepository.parseHashedSegments(json, "YQHsXMglC9A"))
            .containsExactly(TimedSegment(0.0, 74.9, 366.861), TimedSegment(100.0, 110.0, 0.0))
            .inOrder()
    }

    @Test
    fun `a prefix that doesn't include the video has no segments`() {
        assertThat(SponsorBlockRepository.parseHashedSegments("""[{"videoID":"other","segments":[]}]""", "v")).isEmpty()
    }

    @Test
    fun `a broken answer is reported rather than read as no segments`() {
        assertThrows(SerializationException::class.java) { SponsorBlockRepository.parseHashedSegments("{oops", "v") }
    }
}
