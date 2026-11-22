package io.github.aedev.flow.data.local

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.serialization.json.Json
import org.junit.Test

class SearchHistoryCodecTest {
    private val gson = Gson()
    private val historyType = object : TypeToken<List<SearchHistoryItem>>() {}.type

    private val history =
        listOf(
            SearchHistoryItem(
                id = "a",
                query = "world news",
                timestamp = 1_700_000_000_000L,
                type = SearchType.VOICE,
                filters =
                    SearchFilter(
                        contentType = ContentType.VIDEOS,
                        duration = Duration.OVER_20_MINUTES,
                        uploadDate = UploadDate.TODAY,
                        sortType = SortType.VIEW_COUNT,
                        features = setOf(SearchFeature.FOUR_K, SearchFeature.SUBTITLES),
                        watchState = WatchState.UNWATCHED,
                    ),
            ),
            SearchHistoryItem(id = "b", query = "lofi girl", timestamp = 1_600_000_000_000L, type = SearchType.SUGGESTION),
        )

    @Test
    fun `a blob Gson wrote reads back the same`() {
        assertThat(SearchHistoryCodec.decode(gson.toJson(history))).isEqualTo(history)
    }

    // Key order is not compared: Gson on ART writes keys alphabetically, on the JVM in declaration order.
    @Test
    fun `the codec writes the same keys and values Gson wrote`() {
        assertThat(Json.parseToJsonElement(SearchHistoryCodec.encode(history)))
            .isEqualTo(Json.parseToJsonElement(gson.toJson(history)))
    }

    @Test
    fun `a blob a device wrote with alphabetical keys reads back`() {
        val stored =
            """[{"filters":{"contentType":"ALL","duration":"ANY","features":[],"sortType":"RELEVANCE","uploadDate":"ANY",""" +
                """"watchState":"ANY"},"id":"b851","query":"world news","timestamp":1795375403752,"type":"TEXT"}]"""

        assertThat(SearchHistoryCodec.decode(stored))
            .containsExactly(
                SearchHistoryItem(id = "b851", query = "world news", timestamp = 1795375403752, filters = SearchFilter.DEFAULT),
            )
    }

    @Test
    fun `a blob the codec wrote still reads in a build that uses Gson`() {
        val read = gson.fromJson<List<SearchHistoryItem>>(SearchHistoryCodec.encode(history), historyType)

        assertThat(read).isEqualTo(history)
    }

    @Test
    fun `entries stored before filters and types were kept read with the defaults`() {
        val stored = """[{"id":"a","query":"cats","timestamp":1}]"""

        val read = SearchHistoryCodec.decode(stored).single()

        assertThat(read.type).isEqualTo(SearchType.TEXT)
        assertThat(read.filters).isNull()
    }

    @Test
    fun `values a newer version wrote fall back instead of losing the list`() {
        val stored =
            """[{"id":"a","query":"cats","timestamp":1,"type":"IMAGE","extra":true,""" +
                """"filters":{"contentType":"PODCASTS","duration":"ANY","uploadDate":"TODAY","sortType":"RELEVANCE",""" +
                """"features":["HD","HOLOGRAM"],"watchState":"ANY"}}]"""

        val read = SearchHistoryCodec.decode(stored).single()

        assertThat(read.type).isEqualTo(SearchType.TEXT)
        assertThat(read.filters).isEqualTo(SearchFilter(uploadDate = UploadDate.TODAY, features = setOf(SearchFeature.HD)))
    }

    @Test
    fun `entries without words are dropped and a missing id gets a stable one`() {
        val stored = """[{"id":"a","query":"  ","timestamp":1},{"query":"dogs","timestamp":2}]"""

        val read = SearchHistoryCodec.decode(stored)

        assertThat(read.map { it.query }).containsExactly("dogs")
        assertThat(read.single().id).isEqualTo(SearchHistoryCodec.decode(stored).single().id)
    }

    @Test
    fun `a missing or broken blob reads as no history`() {
        assertThat(SearchHistoryCodec.decode(null)).isEmpty()
        assertThat(SearchHistoryCodec.decode("{not json")).isEmpty()
    }

    @Test
    fun `an item Gson restored with a null enum is written with its default`() {
        val restored = gson.fromJson<List<SearchHistoryItem>>("""[{"id":"a","query":"cats","timestamp":1,"type":"IMAGE"}]""", historyType)

        assertThat(SearchHistoryCodec.decode(SearchHistoryCodec.encode(restored)).single().type).isEqualTo(SearchType.TEXT)
    }
}
