package io.github.aedev.flow.innertube.pages

import io.github.aedev.flow.innertube.models.SongItem
import io.github.aedev.flow.innertube.pages.InnerTubeJson.ARTIST
import io.github.aedev.flow.innertube.pages.InnerTubeJson.ATV
import io.github.aedev.flow.innertube.pages.InnerTubeJson.browseEndpoint
import io.github.aedev.flow.innertube.pages.InnerTubeJson.carousel
import io.github.aedev.flow.innertube.pages.InnerTubeJson.run
import io.github.aedev.flow.innertube.pages.InnerTubeJson.songRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomePageSectionTest {
    private val michael = run("Michael Jackson", browseEndpoint("UCmichael", ARTIST))

    private val quickPicks =
        InnerTubeJson
            .browse(listOf(carousel("Quick picks", listOf(songRow("s1", "Beat It", ATV, listOf(michael))))))
            .contents
            ?.sectionListRenderer
            ?.contents
            ?.single()
            ?.musicCarouselShelfRenderer!!

    @Test
    fun `a chip's song rows are kept`() {
        val section = HomePage.Section.fromMusicCarouselShelfRenderer(quickPicks, includeSongRows = true)

        val song = section?.items?.single() as SongItem
        assertEquals("Quick picks", section.title)
        assertEquals("s1", song.id)
        assertEquals("Beat It", song.title)
        assertEquals(listOf("Michael Jackson"), song.artists.map { it.name })
    }

    @Test
    fun `the normal home leaves song rows out`() {
        assertNull(HomePage.Section.fromMusicCarouselShelfRenderer(quickPicks))
    }
}
