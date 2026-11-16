package io.github.aedev.flow.data.local

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.StringReader

class YouTubeTakeoutHtmlReaderTest {
    private fun cell(
        videoId: String,
        title: String,
        channelId: String,
        channel: String,
    ) = """<div class="content-cell mdl-cell">Watched <a href="https://www.youtube.com/watch?v=$videoId">$title</a><br>""" +
        """<a href="https://www.youtube.com/channel/$channelId">$channel</a><br>Sep 3, 2026</div>"""

    private val why =
        "<b>Why is this here?</b><br>&emsp;This activity was saved to your Google Account.&nbsp;" +
            "<a href=\"https://myaccount.google.com/activitycontrols\">here</a>."

    /** One entry as Takeout lays it out; [details] is the block only ads carry, in any language. */
    private fun entry(
        videoId: String,
        title: String,
        channel: Pair<String, String>? = null,
        details: String? = null,
        products: String = "Products:",
        whyBlock: String = why,
    ): String {
        val channelLink = channel?.let { (id, name) -> """<a href="https://www.youtube.com/channel/$id">$name</a><br>""" }.orEmpty()
        val detailsBlock = details?.let { "<b>$it</b><br>&emsp;From Google Ads<br>" }.orEmpty()
        return """<div class="outer-cell mdl-cell mdl-cell--12-col mdl-shadow--2dp"><div class="mdl-grid">""" +
            """<div class="header-cell mdl-cell mdl-cell--12-col"><p class="mdl-typography--title">YouTube<br></p></div>""" +
            """<div class="content-cell mdl-cell mdl-cell--6-col mdl-typography--body-1">Watched """ +
            """<a href="https://www.youtube.com/watch?v=$videoId">$title</a><br>$channelLink""" +
            "Oct 4, 2026, 10:56:07 PM EEST<br></div>" +
            """<div class="content-cell mdl-cell mdl-cell--6-col mdl-typography--body-1 mdl-typography--text-right"></div>""" +
            """<div class="content-cell mdl-cell mdl-cell--12-col mdl-typography--caption">""" +
            "<b>$products</b><br>&emsp;YouTube<br>$detailsBlock$whyBlock</div></div></div>"
    }

    private fun page(vararg entries: String) =
        """<html><head><style>.outer-cell{}</style></head><body><div class="mdl-grid">""" +
            entries.joinToString("") + "</div></body></html>"

    private suspend fun read(
        html: String,
        requireActivityMarkup: Boolean = true,
    ): Pair<TakeoutHtmlActivity, List<TakeoutWatch>> {
        val seen = mutableListOf<TakeoutWatch>()
        val result = readTakeoutHtmlActivity(StringReader(html), now = 1_000, requireActivityMarkup = requireActivityMarkup) { seen += it }
        return result to seen
    }

    @Test
    fun `each watched video comes with its channel, newest first`() =
        runTest {
            val (result, watches) =
                read(
                    cell("dQw4w9WgXcQ", "Never &amp; Ever", "UCuAXFkgsw1L7xaCfnd5JJOw", "Rick") +
                        cell("kcxK1Tnwy5M", "Second", "UC2", "Other"),
                )

            assertThat(result.watches).isEqualTo(2)
            assertThat(watches.map { it.videoId }).containsExactly("dQw4w9WgXcQ", "kcxK1Tnwy5M").inOrder()
            assertThat(watches.first().title).isEqualTo("Never & Ever")
            assertThat(watches.first().channelName).isEqualTo("Rick")
            assertThat(watches.first().channelId).isEqualTo("UCuAXFkgsw1L7xaCfnd5JJOw")
            assertThat(watches.map { it.watchedAt }).containsExactly(1_000L, 999L).inOrder()
        }

    @Test
    fun `other html in the archive gives nothing unless it is known to be history`() =
        runTest {
            val page = """<html><a href="https://www.youtube.com/watch?v=dQw4w9WgXcQ">Link</a></html>"""

            assertThat(read(page).first.watches).isEqualTo(0)
            assertThat(read(page).second).isEmpty()
            assertThat(read(page, requireActivityMarkup = false).first.watches).isEqualTo(1)
        }

    @Test
    fun `youtube music watches and searches from both services are read too`() =
        runTest {
            val html =
                """<div class="content-cell">Watched <a href="https://music.youtube.com/watch?v=ccccccccccc">Song</a></div>""" +
                    """<div class="content-cell">Searched for """ +
                    """<a href="https://www.youtube.com/results?search_query=lofi+%26+rain">lofi &amp; rain</a></div>""" +
                    """<div class="content-cell">Searched for <a href="https://music.youtube.com/search?q=jazz">jazz</a></div>"""

            val (result, watches) = read(html)

            assertThat(watches.single().isMusic).isTrue()
            assertThat(result.searches.map { it.query to it.isMusic }).containsExactly("lofi & rain" to false, "jazz" to true).inOrder()
        }

    @Test
    fun `ads are left out and reported, in any language (1944)`() =
        runTest {
            val (result, watches) =
                read(
                    page(
                        entry("aaaaaaaaaaa", "Real one", "UC1" to "Chan"),
                        entry("bbbbbbbbbbb", "Pizza 50%", details = "Details:"),
                        entry(
                            "ccccccccccc",
                            "Werbung",
                            details = "Einzelheiten:",
                            products = "Produkte:",
                            whyBlock = why.replace("Why is this here?", "Warum wird das angezeigt?"),
                        ),
                        entry("ddddddddddd", "Real two", "UC2" to "Other"),
                    ),
                )

            assertThat(watches.map { it.videoId }).containsExactly("aaaaaaaaaaa", "ddddddddddd").inOrder()
            assertThat(watches.map { it.watchedAt }).containsExactly(1_000L, 999L).inOrder()
            assertThat(result.watches).isEqualTo(2)
            assertThat(result.adVideoIds).containsExactly("bbbbbbbbbbb", "ccccccccccc")
        }

    @Test
    fun `an entry with no channel link never takes the next entry's channel`() =
        runTest {
            val (_, watches) = read(page(entry("aaaaaaaaaaa", "Removed video"), entry("bbbbbbbbbbb", "Next", "UC2" to "Other")))

            assertThat(watches.map { it.channelId }).containsExactly("", "UC2").inOrder()
        }

    @Test
    fun `entries split across reads are read whole`() =
        runTest {
            val entries =
                (0 until 400).map { i ->
                    val id = "v" + i.toString().padStart(10, '0')
                    if (i % 10 == 3) entry(id, "Ad $i", details = "Details:") else entry(id, "Video $i", "UC$i" to "Channel $i")
                }
            val html = page(*entries.toTypedArray())
            check(html.length > 3 * 65_536)

            val (result, watches) = read(html)

            assertThat(result.adVideoIds).hasSize(40)
            assertThat(watches).hasSize(360)
            assertThat(watches.all { it.channelId == "UC" + it.title.removePrefix("Video ") }).isTrue()
            assertThat(watches.map { it.watchedAt }).isEqualTo((0 until 360).map { 1_000L - it })
        }

    @Test
    fun `a caption is an ad only with a second block that has no link`() {
        val normal = entry("aaaaaaaaaaa", "x")
        val noWhy = entry("aaaaaaaaaaa", "x", whyBlock = "")
        val ad = entry("aaaaaaaaaaa", "x", details = "Details:")
        val adNoWhy = entry("aaaaaaaaaaa", "x", details = "Details:", whyBlock = "")

        assertThat(isTakeoutAd(normal)).isFalse()
        assertThat(isTakeoutAd(noWhy)).isFalse()
        assertThat(isTakeoutAd(ad)).isTrue()
        assertThat(isTakeoutAd(adNoWhy)).isTrue()
        assertThat(isTakeoutAd("<div class=\"content-cell\">Watched</div>")).isFalse()
    }
}
