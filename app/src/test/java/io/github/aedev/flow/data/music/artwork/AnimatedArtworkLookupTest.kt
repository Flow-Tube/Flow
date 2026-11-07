package io.github.aedev.flow.data.music.artwork

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.music.model.MusicArtist
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.innertube.models.Album
import io.github.aedev.flow.innertube.models.Artist
import io.github.aedev.flow.innertube.models.SongItem
import io.github.aedev.flow.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_ATV
import io.github.aedev.flow.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_OMV
import org.junit.Test

class AnimatedArtworkLookupTest {
    private val video =
        MusicTrack(
            videoId = "huxhqphtDrM",
            title = "Besharam Rang",
            artist = "Shilpa Rao",
            thumbnailUrl = "",
            duration = 194,
            isVideoSong = true,
            artists = listOf(MusicArtist("Shilpa Rao"), MusicArtist("Caralisa Monteiro")),
        )

    private fun row(
        title: String = "Besharam Rang (From \"Pathaan\")",
        artists: List<String> = listOf("Shilpa Rao", "Vishal-Shekhar"),
        album: String? = "Pathaan",
        duration: Int? = 259,
        type: String? = MUSIC_VIDEO_TYPE_ATV,
    ) = SongItem(
        id = title,
        title = title,
        artists = artists.map { Artist(it, null) },
        album = album?.let { Album(it, it) },
        duration = duration,
        musicVideoType = type,
        thumbnail = "",
    )

    @Test
    fun `the search asks for the plain title and the artist`() {
        val bracketed = video.copy(title = "Besharam Rang (Official Video)", artists = emptyList(), artist = "Shilpa Rao - Topic")

        assertThat(AnimatedArtworkLookup.songSearch(bracketed)).isEqualTo("Besharam Rang Shilpa Rao")
    }

    @Test
    fun `the song version keeps its own title, album and length`() {
        val version = AnimatedArtworkLookup.albumVersion(video, listOf(row()))

        assertThat(version?.title).isEqualTo("Besharam Rang (From \"Pathaan\")")
        assertThat(version?.album).isEqualTo("Pathaan")
        assertThat(version?.duration).isEqualTo(259)
        assertThat(version?.videoId).isEqualTo("huxhqphtDrM")
    }

    @Test
    fun `a row without a length does not send the video's`() {
        assertThat(AnimatedArtworkLookup.albumVersion(video, listOf(row(duration = null)))?.duration).isEqualTo(0)
    }

    @Test
    fun `the first matching row wins`() {
        val rows = listOf(row(album = "Pathaan"), row(album = "Viral Hit Mix"))

        assertThat(AnimatedArtworkLookup.albumVersion(video, rows)?.album).isEqualTo("Pathaan")
    }

    @Test
    fun `only song rows count, not music videos or rows of unknown kind`() {
        val rows = listOf(row(album = "Video", type = MUSIC_VIDEO_TYPE_OMV), row(album = "Unknown", type = null), row(album = "Pathaan"))

        assertThat(AnimatedArtworkLookup.albumVersion(video, rows)?.album).isEqualTo("Pathaan")
        assertThat(AnimatedArtworkLookup.albumVersion(video, rows.take(2))).isNull()
    }

    @Test
    fun `another song or another artist is not the song version`() {
        val rows =
            listOf(
                row(title = "Besharam Rang - LoFi Mix (From \"Pathaan\")"),
                row(title = "Besharam Rang (Unplugged)", artists = listOf("Esha Chauhan")),
                row(title = "Chaleya", artists = listOf("Arijit Singh", "Shilpa Rao")),
            )

        assertThat(AnimatedArtworkLookup.albumVersion(video, rows)).isNull()
    }

    @Test
    fun `a row with no album is passed over`() {
        val rows = listOf(row(album = null), row(album = ""), row(album = "Pathaan"))

        assertThat(AnimatedArtworkLookup.albumVersion(video, rows)?.album).isEqualTo("Pathaan")
    }

    @Test
    fun `the Gehra Hua answer the service gives is trusted`() {
        val gehraHua =
            MusicTrack(
                videoId = "i1o1p_DD6TU",
                title = "Gehra Hua (From \"Dhurandhar\")",
                artist = "Shashwat Sachdev",
                thumbnailUrl = "",
                duration = 363,
                album = "Gehra Hua (From \"Dhurandhar\")",
                artists = listOf(MusicArtist("Shashwat Sachdev"), MusicArtist("Arijit Singh")),
            )

        assertThat(
            AnimatedArtworkLookup.accepts(
                gehraHua,
                ArtworkAnswer("Gehra Hua (From \"Dhurandhar\")", "Shashwat Sachdev, Arijit Singh, Irshad Kamil & Armaan Khan"),
            ),
        ).isTrue()
    }

    private val huaMain =
        MusicTrack(
            videoId = "h",
            title = "Hua Main",
            artist = "Raghav Chaitanya",
            thumbnailUrl = "",
            duration = 278,
            album = "Animal- Hindi",
            artists = listOf(MusicArtist("Raghav Chaitanya", "rc")),
        )

    private fun release(
        id: Long,
        album: String,
        tracks: Int = 10,
        title: String = "Hua Main",
        artist: String = "Raghav Chaitanya & Pritam",
        albumArtist: String = "Pritam",
    ) = CatalogSong(title, artist, id, album, albumArtist, tracks)

    @Test
    fun `other releases skip the album already asked and put full albums before singles`() {
        val ids =
            AnimatedArtworkLookup.otherReleases(
                huaMain,
                listOf(
                    release(1, "Hua Main (From \"ANIMAL\") - Single", tracks = 1),
                    release(2, "ANIMAL (Deluxe Edition Album)", tracks = 18),
                    release(3, "ANIMAL (Original Motion Picture Soundtrack)", tracks = 9),
                ),
                asked = setOf("2"),
            )

        assertThat(ids).containsExactly("3", "1").inOrder()
    }

    @Test
    fun `other releases leave out compilations, remixes and other songs`() {
        val ids =
            AnimatedArtworkLookup.otherReleases(
                huaMain,
                listOf(
                    release(1, "Vanga Vibes"),
                    release(2, "Animal Hits", albumArtist = "Various Artists"),
                    release(3, "Hua Main (Lofi Flip) - Single"),
                    release(4, "ANIMAL", title = "Satranga"),
                    release(5, "ANIMAL", artist = "Someone Else"),
                ),
                asked = emptySet(),
            )

        assertThat(ids).isEmpty()
    }

    @Test
    fun `other releases ask about at most two albums, once each`() {
        val ids =
            AnimatedArtworkLookup.otherReleases(
                huaMain,
                listOf(release(1, "ANIMAL"), release(1, "ANIMAL"), release(2, "ANIMAL Deluxe"), release(3, "ANIMAL Volume 3")),
                asked = emptySet(),
            )

        assertThat(ids).containsExactly("1", "2").inOrder()
    }

    @Test
    fun `a remix keeps its own remix releases`() {
        val remix = huaMain.copy(title = "Hua Main (Lofi Flip)", album = "")

        val ids =
            AnimatedArtworkLookup.otherReleases(
                remix,
                listOf(release(1, "Hua Main (Lofi Flip) - Single", title = "Hua Main (Lofi Flip)")),
                emptySet(),
            )

        assertThat(ids).containsExactly("1")
    }
}
