package io.github.aedev.flow.data.local

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class YouTubeTakeoutCsvParserTest {
    private val channelId = "UC${"a".repeat(22)}"
    private val videoId = "aB3_dE7-fG9"

    @Test
    fun `takeout entry accepts localized YouTube paths only`() {
        assertThat(isYouTubeTakeoutCsvEntry("Takeout/YouTube وYouTube Music/الاشتراكات_/الاشتراكات_.csv")).isTrue()
        assertThat(isYouTubeTakeoutCsvEntry("takeout/YouTube and YouTube Music/playlists/list-videos.CSV")).isTrue()

        assertThat(isYouTubeTakeoutCsvEntry("Takeout/Drive/subscriptions.csv")).isFalse()
        assertThat(isYouTubeTakeoutCsvEntry("Takeout/FakeYouTube/subscriptions.csv")).isFalse()
        assertThat(isYouTubeTakeoutCsvEntry("Takeout/YouTubeBackup/subscriptions.csv")).isFalse()
        assertThat(isYouTubeTakeoutCsvEntry("Takeout/YouTube backup/subscriptions.csv")).isFalse()
        assertThat(isYouTubeTakeoutCsvEntry("Takeout/YouTube and YouTube Music backup/subscriptions.csv")).isFalse()
        assertThat(isYouTubeTakeoutCsvEntry("Takeout/YouTube/../subscriptions.csv")).isFalse()
        assertThat(isYouTubeTakeoutCsvEntry("subscriptions.csv")).isFalse()
        assertThat(isYouTubeTakeoutCsvEntry("Takeout/YouTube/subscriptions.json")).isFalse()
    }

    @Test
    fun `takeout html entry accepts history files in any language`() {
        assertThat(isYouTubeTakeoutHtmlEntry("Takeout/YouTube and YouTube Music/history/watch-history.html")).isTrue()
        assertThat(isYouTubeTakeoutHtmlEntry("Takeout/YouTube і YouTube Music/історія/історія переглядів.html")).isTrue()
        assertThat(isYouTubeTakeoutHtmlEntry("Takeout\\YouTube وYouTube Music\\السجل\\سجل المشاهدة.HTML")).isTrue()

        assertThat(isYouTubeTakeoutHtmlEntry("Takeout/YouTube і YouTube Music/історія.html")).isFalse()
        assertThat(isYouTubeTakeoutHtmlEntry("Takeout/YouTube і YouTube Music/відео/канал/відео.html")).isFalse()
        assertThat(isYouTubeTakeoutHtmlEntry("Takeout/Мої дії/YouTube/Мої дії.html")).isFalse()
        assertThat(isYouTubeTakeoutHtmlEntry("Takeout/YouTube і YouTube Music/історія/історія переглядів.json")).isFalse()
        assertThat(isYouTubeTakeoutHtmlEntry("Takeout/YouTube і YouTube Music/../історія.html")).isFalse()
    }

    @Test
    fun `takeout parent path associates playlist files within one directory`() {
        val metadata = "Takeout/YouTube وYouTube Music/قوائم التشغيل/قوائم التشغيل.csv"
        val playlist = "Takeout\\YouTube وYouTube Music\\قوائم التشغيل\\فيديوهات _Road_.csv"
        val unrelated = "Takeout/YouTube وYouTube Music/السجل/سجل المشاهدة.csv"

        assertThat(metadata.takeoutParentPath()).isEqualTo(playlist.takeoutParentPath())
        assertThat(unrelated.takeoutParentPath()).isNotEqualTo(metadata.takeoutParentPath())
    }

    @Test
    fun `localized subscription csv is classified by validated rows`() {
        val csv =
            "\uFEFFمعرّف القناة,عنوان Url للقناة,عنوان القناة\r\n" +
                "$channelId,https://www.youtube.com/channel/$channelId,\"News, \"\"Live\"\"\""

        assertThat(parse(csv))
            .isEqualTo(
                YouTubeTakeoutCsvContent.Subscriptions(
                    listOf(YouTubeTakeoutSubscription(channelId, "News, \"Live\"")),
                ),
            )
    }

    @Test
    fun `subscription csv accepts one valid row and trailing slash`() {
        val csv =
            """
            Channel Id,Channel Url,Channel Title
            $channelId,http://youtube.com/channel/$channelId/,Channel
            """.trimIndent()

        assertThat(parse(csv))
            .isEqualTo(
                YouTubeTakeoutCsvContent.Subscriptions(
                    listOf(YouTubeTakeoutSubscription(channelId, "Channel")),
                ),
            )
    }

    @Test
    fun `subscription csv rejects unsafe urls and mismatched ids`() {
        val otherChannelId = "UC${"b".repeat(22)}"
        val rejectedUrls =
            listOf(
                "https://youtube.com.evil.example/channel/$channelId",
                "https://user@youtube.com/channel/$channelId",
                "https://youtube.com:443/channel/$channelId",
                "https://youtube.com/channel/$channelId?source=takeout",
                "https://youtube.com/channel/$otherChannelId",
                "https://youtube.com/channel/$channelId//",
            )

        rejectedUrls.forEach { url ->
            val csv = "Channel Id,Channel Url,Channel Title\n$channelId,$url,Channel"

            assertThat(parse(csv)).isEqualTo(YouTubeTakeoutCsvContent.Unsupported)
        }
    }

    @Test
    fun `playlist video csv is classified independently of header language`() {
        val csv =
            """
            معرّف الفيديو,الطابع الزمني لإنشاء الفيديو في قائمة التشغيل
            $videoId,2026-08-30T09:38:42+00:00
            ZYXwvut-987,2026-08-30T09:38:42.123Z
            """.trimIndent()

        assertThat(parse(csv))
            .isEqualTo(YouTubeTakeoutCsvContent.PlaylistVideos(listOf(videoId, "ZYXwvut-987")))
    }

    @Test
    fun `playlist video csv accepts a blank timestamp`() {
        val csv = "Video ID,Created At\n$videoId,"

        assertThat(parse(csv))
            .isEqualTo(YouTubeTakeoutCsvContent.PlaylistVideos(listOf(videoId)))
    }

    @Test
    fun `quoted headers are accepted after a byte order mark`() {
        val csv = "\uFEFF\"Video ID\",\"Created At\"\n$videoId,2026-08-30T09:38:42Z"

        assertThat(parse(csv))
            .isEqualTo(YouTubeTakeoutCsvContent.PlaylistVideos(listOf(videoId)))
    }

    @Test
    fun `playlist video csv rejects malformed or mixed rows`() {
        val malformedFiles =
            listOf(
                "Video ID,Created At\ntoo-short,2026-08-30T09:38:42Z",
                "Video ID,Created At\n$videoId,not-a-timestamp",
                "Video ID,Created At\n$videoId,2026-08-30T09:38:42Z,extra",
                "Video ID,Created At\n\"$videoId,2026-08-30T09:38:42Z",
            )

        malformedFiles.forEach { csv ->
            assertThat(parse(csv)).isEqualTo(YouTubeTakeoutCsvContent.Unsupported)
        }
    }

    @Test
    fun `invalid rows are skipped without discarding the file`() {
        val playlistVideos =
            "Video ID,Created At\n" +
                "$videoId,2026-08-30T09:38:42Z\n" +
                "invalid,row\n" +
                "ZYXwvut-987,2026-08-30T09:38:43Z"
        val subscriptions =
            "Channel Id,Channel Url,Channel Title\n" +
                "$channelId,https://www.youtube.com/channel/$channelId,Channel\n" +
                "$channelId,https://www.youtube.com/channel/$channelId,\n" +
                "not-a-channel,https://www.youtube.com/channel/$channelId,Deleted"
        val playlistId = "PL${"c".repeat(20)}"
        val metadata =
            "Playlist ID,c1,c2,c3,c4,c5,c6,c7,c8,c9,Title\n" +
                "$playlistId,,,,,,,,,,Road\n" +
                "WL,,,,,,,,,,Watch later"

        assertThat(parse(playlistVideos))
            .isEqualTo(YouTubeTakeoutCsvContent.PlaylistVideos(listOf(videoId, "ZYXwvut-987")))
        assertThat(parse(subscriptions))
            .isEqualTo(
                YouTubeTakeoutCsvContent.Subscriptions(
                    listOf(YouTubeTakeoutSubscription(channelId, "Channel")),
                ),
            )
        assertThat(parse(metadata))
            .isEqualTo(YouTubeTakeoutCsvContent.PlaylistMetadata(listOf(TakeoutPlaylistInfo(playlistId, "Road", createdAt = null))))
    }

    @Test
    fun `playlist metadata keeps the youtube id and the create timestamp wherever its column is`() {
        val playlistId = "PL${"d".repeat(20)}"
        val metadata =
            "Playlist ID,Add new videos to top,Created,Updated,c4,c5,c6,c7,c8,c9,Title\n" +
                "$playlistId,False,2019-12-01T08:30:00+00:00,2024-06-02T10:00:00+00:00,,,,,,,Favourites 2019"

        val playlist = (parse(metadata) as YouTubeTakeoutCsvContent.PlaylistMetadata).playlists.single()

        assertThat(playlist.id).isEqualTo(playlistId)
        assertThat(playlist.title).isEqualTo("Favourites 2019")
        assertThat(playlist.createdAt).isEqualTo(
            java.time.Instant
                .parse("2019-12-01T08:30:00Z")
                .toEpochMilli(),
        )
    }

    @Test
    fun `the youtube music library songs file is read by its shape`() {
        val csv =
            "Video ID,Song Title,Album Title,Artist Name 1,Artist Name 2\n" +
                "kcxK1Tnwy5M,One More Time,Alive 2007,Daft Punk,\n" +
                "dQw4w9WgXcQ,\"Never Gonna Give You Up\",Whenever You Need Somebody,Rick Astley,"

        assertThat(parse(csv))
            .isEqualTo(
                YouTubeTakeoutCsvContent.MusicLibrarySongs(
                    listOf(
                        TakeoutLibrarySong("kcxK1Tnwy5M", "One More Time", "Alive 2007", "Daft Punk"),
                        TakeoutLibrarySong("dQw4w9WgXcQ", "Never Gonna Give You Up", "Whenever You Need Somebody", "Rick Astley"),
                    ),
                ),
            )
    }

    @Test
    fun `the account's own uploads are not mistaken for the music library`() {
        val channelId = "UC${"e".repeat(22)}"
        val uploads =
            "Video ID,Duration,Language,Channel ID,Title,Created\n" +
                "kcxK1Tnwy5M,215,en,$channelId,My video,2024-01-01T00:00:00+00:00"

        assertThat(parse(uploads)).isEqualTo(YouTubeTakeoutCsvContent.Unsupported)
    }

    @Test
    fun `subscription csv tolerates additional trailing columns`() {
        val csv =
            "Channel Id,Channel Url,Channel Title,Channel Handle\n" +
                "$channelId,https://www.youtube.com/channel/$channelId,Channel,@channel"

        assertThat(parse(csv))
            .isEqualTo(
                YouTubeTakeoutCsvContent.Subscriptions(
                    listOf(YouTubeTakeoutSubscription(channelId, "Channel")),
                ),
            )
    }

    @Test
    fun `oversized csv records remain unsupported`() {
        val oversizedTitle = "a".repeat(65 * 1_024)
        val csv =
            "Channel Id,Channel Url,Channel Title\n" +
                "$channelId,https://youtube.com/channel/$channelId,$oversizedTitle"

        val exception = assertThrows(IllegalArgumentException::class.java) { parse(csv) }

        assertThat(exception).hasMessageThat().isEqualTo("invalid_format")
    }

    @Test
    fun `archive csv budget rejects aggregate row and entry overflow`() {
        val rowException =
            assertThrows(IllegalArgumentException::class.java) {
                YouTubeTakeoutCsvBudget().acceptContent(rows = 500_001, characters = 0)
            }
        val characterException =
            assertThrows(IllegalArgumentException::class.java) {
                YouTubeTakeoutCsvBudget().acceptContent(rows = 1, characters = 16 * 1_024 * 1_024 + 1)
            }
        val entryBudget = YouTubeTakeoutCsvBudget()
        repeat(10_000) { entryBudget.startEntry() }
        val entryException = assertThrows(IllegalArgumentException::class.java) { entryBudget.startEntry() }

        assertThat(rowException).hasMessageThat().isEqualTo("invalid_format")
        assertThat(characterException).hasMessageThat().isEqualTo("invalid_format")
        assertThat(entryException).hasMessageThat().isEqualTo("invalid_format")
    }

    @Test
    fun `rejected csv content does not consume the accepted content budget`() {
        val budget = YouTubeTakeoutCsvBudget()
        budget.acceptContent(rows = 1, characters = 16 * 1_024 * 1_024 - videoId.length)
        val rejectedCsv =
            "Channel Id,Channel Url,Channel Title\n" +
                "$channelId,https://youtube.com/channel/$channelId,${"a".repeat(100)}\n" +
                "\"unterminated"

        assertThat(readYouTubeTakeoutCsv(rejectedCsv.reader().buffered(), budget))
            .isEqualTo(YouTubeTakeoutCsvContent.Unsupported)

        val validCsv = "Video ID,Created At\n$videoId,2026-08-30T09:38:42Z"
        assertThat(readYouTubeTakeoutCsv(validCsv.reader().buffered(), budget))
            .isEqualTo(YouTubeTakeoutCsvContent.PlaylistVideos(listOf(videoId)))
    }

    @Test
    fun `csv record limit accepts its boundary and rejects the next row`() {
        val header = "Video ID,Created At\n"
        val row = "$videoId,2026-08-30T09:38:42Z\n"
        val atLimit = parse(header + row.repeat(99_999)) as YouTubeTakeoutCsvContent.PlaylistVideos

        assertThat(atLimit.videoIds).hasSize(99_999)
        val exception = assertThrows(IllegalArgumentException::class.java) { parse(header + row.repeat(100_000)) }
        assertThat(exception).hasMessageThat().isEqualTo("invalid_format")
    }

    @Test
    fun `unrelated YouTube csv schemas remain unsupported`() {
        val unrelatedCsvFiles =
            listOf(
                "Video ID,Duration,Category,Channel ID,Title\n$videoId,1000,Music,$channelId,Title",
                "Channel ID,Title,Visibility\n$channelId,Channel,Public",
                "First,Second,Third\none,two,three",
            )

        unrelatedCsvFiles.forEach { csv ->
            assertThat(parse(csv)).isEqualTo(YouTubeTakeoutCsvContent.Unsupported)
        }
    }

    @Test
    fun `headerless and empty csv files remain unsupported`() {
        val headerless =
            "$videoId,2026-08-30T09:38:42Z\nZYXwvut-987,2026-08-30T09:38:43Z"

        assertThat(parse(headerless)).isEqualTo(YouTubeTakeoutCsvContent.Unsupported)
        assertThat(parse("Video ID,Created At")).isEqualTo(YouTubeTakeoutCsvContent.Unsupported)
        assertThat(parse("")).isEqualTo(YouTubeTakeoutCsvContent.Unsupported)
    }

    @Test
    fun `playlist metadata restores names from localized filename wrappers`() {
        val playlistId = "PL${"c".repeat(20)}"
        val metadataCsv =
            "المعرّف,c1,c2,c3,c4,c5,c6,c7,c8,c9,العنوان,c11,c12,c13,c14,c15\n" +
                "$playlistId,,,,,,,,,,\"Japan's, favorites\",,,,,"
        val metadata = parse(metadataCsv) as YouTubeTakeoutCsvContent.PlaylistMetadata
        val wrappedFilename = "Takeout/YouTube وYouTube Music/قوائم تشغيل/فيديوهات _Japan_s, favorites_.csv"

        assertThat(resolvePlaylistNames(listOf(wrappedFilename), metadata.playlists.map { it.title }))
            .containsExactly(wrappedFilename, "Japan's, favorites")
    }

    @Test
    fun `playlist name falls back to the leaf English filename`() {
        val filename = "Takeout/YouTube and YouTube Music/playlists/My List-videos.csv"

        assertThat(resolvePlaylistNames(listOf(filename), emptyList()))
            .containsExactly(filename, "My List")
    }

    @Test
    fun `playlist name falls back to filenames with a space before videos`() {
        val cyberFilename = "Takeout/YouTube and YouTube Music/playlists/Cyber videos.csv"
        val copyFilename = "Takeout/YouTube and YouTube Music/playlists/Watch Later videos(1).csv"

        assertThat(resolvePlaylistNames(listOf(cyberFilename, copyFilename), emptyList()))
            .containsExactly(cyberFilename, "Cyber", copyFilename, "Watch Later")
    }

    @Test
    fun `playlist names match titles when filenames use a space before videos`() {
        val cyberFilename = "Takeout/YouTube and YouTube Music/playlists/Cyber videos.csv"
        val gameFilename = "Takeout/YouTube and YouTube Music/playlists/Game Soundtracks videos.csv"

        assertThat(
            resolvePlaylistNames(
                listOf(cyberFilename, gameFilename),
                listOf("Game Soundtracks", "Cyber"),
            ),
        ).containsExactly(cyberFilename, "Cyber", gameFilename, "Game Soundtracks")
    }

    @Test
    fun `playlist name matching ignores parent directory text`() {
        val roadFilename = "Takeout/YouTube and YouTube Music/playlists/Road-videos.csv"
        val musicFilename = "Takeout/YouTube and YouTube Music/playlists/Music-videos.csv"

        assertThat(
            resolvePlaylistNames(
                listOf(roadFilename, musicFilename),
                listOf("Music", "Road"),
            ),
        ).containsExactly(
            roadFilename,
            "Road",
            musicFilename,
            "Music",
        )
    }

    @Test
    fun `playlist name matching ignores filename wrapper text`() {
        val roadFilename = "Takeout/YouTube and YouTube Music/playlists/Road-videos.csv"
        val videosFilename = "Takeout/YouTube and YouTube Music/playlists/Videos-videos.csv"

        assertThat(
            resolvePlaylistNames(
                listOf(roadFilename, videosFilename),
                listOf("Videos", "Road"),
            ),
        ).containsExactly(
            roadFilename,
            "Road",
            videosFilename,
            "Videos",
        )
    }

    @Test
    fun `playlist name matching preserves one character titles`() {
        val firstFilename = "Takeout/YouTube and YouTube Music/playlists/A-videos.csv"
        val secondFilename = "Takeout/YouTube and YouTube Music/playlists/B-videos.csv"

        assertThat(resolvePlaylistNames(listOf(firstFilename, secondFilename), listOf("B", "A")))
            .containsExactly(
                firstFilename,
                "A",
                secondFilename,
                "B",
            )
    }

    @Test
    fun `playlist name matching preserves symbol only titles`() {
        val firstFilename = "Takeout/YouTube وYouTube Music/قوائم تشغيل/فيديوهات _😀_.csv"
        val secondFilename = "Takeout/YouTube وYouTube Music/قوائم تشغيل/فيديوهات _🎵_.csv"

        assertThat(resolvePlaylistNames(listOf(firstFilename, secondFilename), listOf("🎵", "😀")))
            .containsExactly(
                firstFilename,
                "😀",
                secondFilename,
                "🎵",
            )
    }

    @Test
    fun `playlist name matching preserves duplicate titles`() {
        val firstFilename = "Takeout/YouTube and YouTube Music/playlists/Road-videos.csv"
        val secondFilename = "Takeout/YouTube and YouTube Music/playlists/Road (1)-videos.csv"

        assertThat(resolvePlaylistNames(listOf(firstFilename, secondFilename), listOf("Road", "Road")))
            .containsExactly(
                firstFilename,
                "Road",
                secondFilename,
                "Road",
            )
    }

    @Test
    fun `normalized title collisions do not swap playlist names`() {
        val firstFilename = "Takeout/YouTube and YouTube Music/playlists/Japans-videos.csv"
        val secondFilename = "Takeout/YouTube and YouTube Music/playlists/Japan_s-videos.csv"

        assertThat(
            resolvePlaylistNames(
                listOf(firstFilename, secondFilename),
                listOf("Japan's", "Japans"),
            ),
        ).containsExactly(
            firstFilename,
            "Japans",
            secondFilename,
            "Japan_s",
        )
    }

    @Test
    fun `playlist name matching rejects excessive playlist counts`() {
        val filenames = (0..2_000).map { index -> "Takeout/YouTube/playlists/List $index-videos.csv" }

        val exception =
            assertThrows(IllegalArgumentException::class.java) {
                resolvePlaylistNames(filenames, emptyList())
            }

        assertThat(exception).hasMessageThat().isEqualTo("invalid_format")
    }

    @Test
    fun `unsafe fallback playlist names use the generic name`() {
        val controlFilename = "Takeout/YouTube and YouTube Music/playlists/Bad\u0001Name-videos.csv"
        val separatorFilename = "Takeout/YouTube and YouTube Music/playlists/Bad\u2028Name-videos.csv"

        assertThat(resolvePlaylistNames(listOf(controlFilename, separatorFilename), emptyList()))
            .containsExactly(
                controlFilename,
                "Imported Playlist",
                separatorFilename,
                "Imported Playlist",
            )
    }

    private fun parse(csv: String): YouTubeTakeoutCsvContent = readYouTubeTakeoutCsv(csv.reader().buffered())

    private fun resolvePlaylistNames(
        filenames: Collection<String>,
        metadataTitles: Collection<String>,
    ): Map<String, String> = resolveYouTubeTakeoutPlaylistNames(filenames, metadataTitles, "Imported Playlist")
}
