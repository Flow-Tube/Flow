package io.github.aedev.flow.innertube.pages

import io.github.aedev.flow.innertube.models.MusicResponsiveListItemRenderer
import io.github.aedev.flow.innertube.models.response.BrowseResponse
import io.github.aedev.flow.innertube.models.response.NextResponse
import io.github.aedev.flow.innertube.models.response.SearchResponse
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

@OptIn(ExperimentalSerializationApi::class)
internal object InnerTubeJson {
    const val ARTIST = "MUSIC_PAGE_TYPE_ARTIST"
    const val ALBUM = "MUSIC_PAGE_TYPE_ALBUM"
    const val PLAYLIST = "MUSIC_PAGE_TYPE_PLAYLIST"
    const val USER = "MUSIC_PAGE_TYPE_USER_CHANNEL"
    const val UNKNOWN = "MUSIC_PAGE_TYPE_UNKNOWN"
    const val DISCOGRAPHY = "MUSIC_PAGE_TYPE_ARTIST_DISCOGRAPHY"
    const val PODCAST = "MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE"
    const val ATV = "MUSIC_VIDEO_TYPE_ATV"
    const val OMV = "MUSIC_VIDEO_TYPE_OMV"
    const val UGC = "MUSIC_VIDEO_TYPE_UGC"
    const val CIRCLE = "MUSIC_THUMBNAIL_CROP_CIRCLE"
    private const val SQUARE = "MUSIC_THUMBNAIL_CROP_UNSPECIFIED"

    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    fun browse(
        sections: List<String>,
        header: String? = null,
        singleColumn: Boolean = false,
    ): BrowseResponse {
        val sectionList = """{"sectionListRenderer":{"contents":[${sections.joinToString(",")}]}}"""
        val contents =
            if (singleColumn) {
                """{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":$sectionList}}]}}"""
            } else {
                sectionList
            }
        val headerField = header?.let { ""","header":$it""" }.orEmpty()
        return json.decodeFromString(BrowseResponse.serializer(), """{"responseContext":{},"contents":$contents$headerField}""")
    }

    fun search(sections: List<String>): SearchResponse =
        json.decodeFromString(
            SearchResponse.serializer(),
            """{"contents":{"tabbedSearchResultsRenderer":{"tabs":[{"tabRenderer":{"content":
            |{"sectionListRenderer":{"contents":[${sections.joinToString(",")}]}}}}]}}}
            """.trimMargin(),
        )

    fun searchWithChips(
        sections: List<String>,
        chipParams: List<String>,
    ): SearchResponse {
        val chips =
            chipParams.joinToString(",") {
                """{"chipCloudChipRenderer":{"isSelected":false,"text":${runs(run(it))},
                |"navigationEndpoint":{"searchEndpoint":{"query":"q","params":"$it"}}}}
                """.trimMargin()
            }
        return json.decodeFromString(
            SearchResponse.serializer(),
            """{"contents":{"tabbedSearchResultsRenderer":{"tabs":[{"tabRenderer":{"content":
            |{"sectionListRenderer":{"header":{"chipCloudRenderer":{"chips":[$chips]}},"contents":[${sections.joinToString(",")}]}}}}]}}}
            """.trimMargin(),
        )
    }

    /** The row object inside a `{"musicResponsiveListItemRenderer":…}` wrapper. */
    fun decodeRow(row: String): MusicResponsiveListItemRenderer =
        json.decodeFromString(MusicResponsiveListItemRenderer.serializer(), row.substringAfter(":").removeSuffix("}"))

    /** A filtered episode row: no "Episode" label, the show linked after the date. */
    fun episodeRow(
        videoId: String,
        title: String,
        date: String,
        show: String,
    ) = """{"musicResponsiveListItemRenderer":{"thumbnail":${thumbnail(videoId, SQUARE)},
        |"flexColumns":[${flexColumn(run(title, browseEndpoint("MPED$videoId", "MUSIC_PAGE_TYPE_NON_MUSIC_AUDIO_TRACK_PAGE")))},
        |${flexColumn(run(date), separator(), run(show, browseEndpoint("MPSP$show", "MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE")))}],
        |"playlistItemData":{"videoId":"$videoId"},
        |"overlay":${playOverlay(watchEndpoint(videoId, "MUSIC_VIDEO_TYPE_PODCAST_EPISODE"))}}}
        """.trimMargin()

    fun podcastRow(
        browseId: String,
        playlistId: String,
        title: String,
        author: String,
    ) = """{"musicResponsiveListItemRenderer":{"thumbnail":${thumbnail(browseId, SQUARE)},
        |"flexColumns":[${flexColumn(run(title))},${flexColumn(run(author, browseEndpoint("UCauthor", USER)))}],
        |"overlay":${playOverlay(watchPlaylistEndpoint(playlistId))},
        |"navigationEndpoint":${browseEndpoint(browseId, "MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE")}}}
        """.trimMargin()

    /** A Podcasts chip episode: a wide row whose second title links its show. */
    fun multiRowEpisode(
        videoId: String,
        title: String,
        show: String,
    ) = """{"musicMultiRowListItemRenderer":{"thumbnail":${thumbnail(videoId, SQUARE)},
        |"title":${runs(run(title, browseEndpoint("MPED$videoId", "MUSIC_PAGE_TYPE_NON_MUSIC_AUDIO_TRACK_PAGE")))},
        |"secondTitle":${runs(run(show, browseEndpoint("MPSPPL$show", "MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE")))},
        |"subtitle":${runs(run("1K views"), separator(), run("Aug 5"))},
        |"onTap":${watchEndpoint(videoId, "MUSIC_VIDEO_TYPE_PODCAST_EPISODE")}}}
        """.trimMargin()

    fun itemSection(row: String) = """{"itemSectionRenderer":{"contents":[$row]}}"""

    /** A search row that opens a browse page: an artist, album or playlist, with play and radio actions. */
    fun browseRow(
        browseId: String,
        pageType: String,
        title: String,
        secondary: List<String>,
        playlistId: String = "PL$browseId",
    ) = """{"musicResponsiveListItemRenderer":{"thumbnail":${thumbnail(browseId, SQUARE)},
        |"flexColumns":[${flexColumn(run(title))},${flexColumn(*secondary.toTypedArray())}],
        |"overlay":${playOverlay(watchPlaylistEndpoint(playlistId))},"menu":${menu(playlistId)},
        |"navigationEndpoint":${browseEndpoint(browseId, pageType)}}}
        """.trimMargin()

    fun topResultArtistCard(
        header: String,
        browseId: String,
        name: String,
    ): String {
        fun button(iconType: String) =
            """{"buttonRenderer":{"text":${runs(run(iconType))},"icon":{"iconType":"$iconType"},
            |"command":${watchPlaylistEndpoint("RD$browseId")}}}
            """.trimMargin()
        return """{"musicCardShelfRenderer":{"header":{"musicCardShelfHeaderBasicRenderer":{"title":${runs(run(header))}}},
            |"title":${runs(run(name))},"subtitle":${runs(run("Artist"))},"thumbnail":${thumbnail(browseId, CIRCLE)},
            |"buttons":[${button("MUSIC_SHUFFLE")},${button("MIX")}],"onTap":${browseEndpoint(browseId, ARTIST)}}}
            """.trimMargin()
    }

    fun next(queueRows: List<String>): NextResponse =
        json.decodeFromString(
            NextResponse.serializer(),
            """{"contents":{"singleColumnMusicWatchNextResultsRenderer":{"tabbedRenderer":{"watchNextTabbedResultsRenderer":{"tabs":[
            |{"tabRenderer":{"content":{"musicQueueRenderer":{"content":{"playlistPanelRenderer":{"contents":[${queueRows.joinToString(
                ",",
            )}]}}}}}}
            |]}}}}}
            """.trimMargin(),
        )

    fun run(
        text: String,
        endpoint: String? = null,
    ) = if (endpoint == null) """{"text":"$text"}""" else """{"text":"$text","navigationEndpoint":$endpoint}"""

    fun separator() = run(" • ")

    fun browseEndpoint(
        browseId: String,
        pageType: String,
        params: String? = null,
    ): String {
        val paramsField = params?.let { ""","params":"$it"""" }.orEmpty()
        return """{"browseEndpoint":{"browseId":"$browseId"$paramsField,
            |"browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"$pageType"}}}}
            """.trimMargin()
    }

    fun watchEndpoint(
        videoId: String,
        musicVideoType: String,
    ) =
        """{"watchEndpoint":{"videoId":"$videoId","watchEndpointMusicSupportedConfigs":{"watchEndpointMusicConfig":{"musicVideoType":"$musicVideoType"}}}}"""

    fun songRow(
        videoId: String,
        title: String,
        musicVideoType: String,
        secondary: List<String>,
        album: String? = null,
    ): String {
        val columns =
            listOfNotNull(
                flexColumn(
                    run(title, watchEndpoint(videoId, musicVideoType)),
                ),
                flexColumn(*secondary.toTypedArray()),
                album?.let {
                    flexColumn(it)
                },
            )
        return """{"musicResponsiveListItemRenderer":{"thumbnail":${thumbnail(
            videoId,
            SQUARE,
        )},"flexColumns":[${columns.joinToString(",")}],
            |"playlistItemData":{"videoId":"$videoId"},"overlay":${playOverlay(watchEndpoint(videoId, musicVideoType))}}}
            """.trimMargin()
    }

    /** A row from the reduced search answer: no playlistItemData, no music video type (#1379). */
    fun bareSongRow(
        videoId: String,
        title: String,
        secondary: List<String>,
    ) = """{"musicResponsiveListItemRenderer":{"thumbnail":${thumbnail(videoId, SQUARE)},
        |"flexColumns":[${flexColumn(run(title))},${flexColumn(*secondary.toTypedArray())}],
        |"overlay":${playOverlay("""{"watchEndpoint":{"videoId":"$videoId"}}""")},
        |"navigationEndpoint":{"watchEndpoint":{"videoId":"$videoId"}}}}
        """.trimMargin()

    /** A browse row from the reduced search answer: no play overlay, and a Mix item only when [withMix]. */
    fun bareBrowseRow(
        browseId: String,
        pageType: String,
        title: String,
        secondary: List<String>,
        playlistId: String? = null,
        withMix: Boolean = true,
    ): String {
        val menu =
            playlistId
                ?.let {
                    val items = listOfNotNull(menuItem("MUSIC_SHUFFLE", it), menuItem("MIX", "RD$it").takeIf { withMix })
                    ""","menu":{"menuRenderer":{"items":[${items.joinToString(",")}]}}"""
                }.orEmpty()
        return """{"musicResponsiveListItemRenderer":{"thumbnail":${thumbnail(browseId, SQUARE)},
            |"flexColumns":[${flexColumn(run(title))},${flexColumn(*secondary.toTypedArray())}]$menu,
            |"navigationEndpoint":${browseEndpoint(browseId, pageType)}}}
            """.trimMargin()
    }

    fun artistRow(
        browseId: String,
        name: String,
        subscribers: String,
    ) = """{"musicResponsiveListItemRenderer":{"thumbnail":${thumbnail(
        browseId,
        CIRCLE,
    )},"flexColumns":[${flexColumn(run(name))},${flexColumn(run(subscribers))}],
        |"navigationEndpoint":${browseEndpoint(browseId, ARTIST)}}}
        """.trimMargin()

    fun twoRow(
        browseId: String,
        pageType: String,
        title: String,
        subtitle: List<String>,
        playlistId: String? = null,
        crop: String = SQUARE,
    ): String {
        val playable =
            playlistId
                ?.let {
                    ""","thumbnailOverlay":${playOverlay(watchPlaylistEndpoint(it))},"menu":${menu(it)}"""
                }.orEmpty()
        return """{"musicTwoRowItemRenderer":{"thumbnailRenderer":${thumbnail(
            browseId,
            crop,
        )},"title":${runs(run(title, browseEndpoint(browseId, pageType)))},
            |"subtitle":${runs(*subtitle.toTypedArray())},"navigationEndpoint":${browseEndpoint(browseId, pageType)}$playable}}
            """.trimMargin()
    }

    fun videoTwoRow(
        videoId: String,
        title: String,
        artist: String,
    ) = """{"musicTwoRowItemRenderer":{"thumbnailRenderer":${thumbnail(
        videoId,
        SQUARE,
    )},"title":${runs(run(title))},"subtitle":${runs(run(artist))},
        |"navigationEndpoint":${watchEndpoint(videoId, OMV)}}}
        """.trimMargin()

    fun carousel(
        title: String,
        items: List<String>,
        titleEndpoint: String? = null,
        strapline: String? = null,
        moreBrowseId: String? = null,
        morePageType: String = DISCOGRAPHY,
        moreParams: String? = null,
    ): String {
        val straplineField = strapline?.let { ""","strapline":${runs(run(it))}""" }.orEmpty()
        val moreField =
            moreBrowseId
                ?.let {
                    ""","moreContentButton":{"buttonRenderer":{"text":${runs(
                        run("More"),
                    )},"navigationEndpoint":${browseEndpoint(it, morePageType, moreParams)}}}"""
                }.orEmpty()
        return """{"musicCarouselShelfRenderer":{"header":{"musicCarouselShelfBasicHeaderRenderer":{"title":${runs(
            run(title, titleEndpoint),
        )}$straplineField$moreField}},
            |"contents":[${items.joinToString(",")}],"itemSize":"COLLECTION_STYLE_ITEM_SIZE_MEDIUM"}}
            """.trimMargin()
    }

    fun musicShelf(
        title: String?,
        items: List<String>,
        formItemKeys: List<String> = emptyList(),
        continuation: String? = null,
    ): String {
        val titleField = title?.let { """"title":${runs(run(it))},""" }.orEmpty()
        val options = formItemKeys.joinToString(",") { """{"musicMultiSelectMenuItemRenderer":{"formItemEntityKey":"$it"}}""" }
        val subheaders =
            if (formItemKeys.isEmpty()) {
                ""
            } else {
                ""","subheaders":[{"musicSideAlignedItemRenderer":{"startItems":[{"musicSortFilterButtonRenderer":{"menu":{"musicMultiSelectMenuRenderer":{"options":[$options]}}}}]}}]"""
            }
        val continuations = continuation?.let { ""","continuations":[{"nextContinuationData":{"continuation":"$it"}}]""" }.orEmpty()
        return """{"musicShelfRenderer":{$titleField"contents":[${items.joinToString(",")}]$subheaders$continuations}}"""
    }

    fun immersiveHeader(
        title: String,
        channelId: String,
        subscribers: String,
        monthlyListeners: String,
    ) = """{"musicImmersiveHeaderRenderer":{"title":${runs(run(title))},"menu":{"menuRenderer":{}},
        |"subscriptionButton":{"subscribeButtonRenderer":{"subscribed":false,"channelId":"$channelId","subscriberCountText":${runs(
        run(subscribers),
    )}}},
        |"monthlyListenerCount":${runs(run(monthlyListeners))}}}
        """.trimMargin()

    fun queueRow(
        videoId: String,
        title: String,
        length: String,
        musicVideoType: String,
        byline: List<String>,
        selected: Boolean = false,
    ) = """{"playlistPanelVideoRenderer":{"title":${runs(run(title))},"longBylineText":${runs(*byline.toTypedArray())},
        |"thumbnail":{"thumbnails":[{"url":"https://t/$videoId","width":400,"height":225}]},"lengthText":${runs(run(length))},
        |"selected":$selected,"navigationEndpoint":${watchEndpoint(videoId, musicVideoType)},"videoId":"$videoId"}}
        """.trimMargin()

    private fun runs(vararg runs: String) = """{"runs":[${runs.joinToString(",")}]}"""

    private fun flexColumn(vararg runs: String) = """{"musicResponsiveListItemFlexColumnRenderer":{"text":${runs(*runs)}}}"""

    private fun thumbnail(
        id: String,
        crop: String,
    ) =
        """{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://t/$id","width":60,"height":60}]},"thumbnailCrop":"$crop"}}"""

    private fun watchPlaylistEndpoint(playlistId: String) = """{"watchPlaylistEndpoint":{"playlistId":"$playlistId"}}"""

    private fun playOverlay(endpoint: String) =
        """{"musicItemThumbnailOverlayRenderer":{"content":{"musicPlayButtonRenderer":{"playNavigationEndpoint":$endpoint}}}}"""

    private fun menuItem(
        iconType: String,
        playlistId: String,
    ) = """{"menuNavigationItemRenderer":{"text":${runs(
        run(iconType),
    )},"icon":{"iconType":"$iconType"},"navigationEndpoint":${watchPlaylistEndpoint(playlistId)}}}"""

    private fun menu(playlistId: String) =
        """{"menuRenderer":{"items":[${menuItem("MUSIC_SHUFFLE", playlistId)},${menuItem("MIX", playlistId)}]}}"""
}
