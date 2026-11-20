package io.github.aedev.flow.innertube.pages

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveMetadataUpdateTest {
    @Test
    fun `reads viewers, date, likes and the next round from a live answer`() {
        val update = Json.parseToJsonElement(LIVE).toLiveMetadataUpdate(hl = "en")

        assertEquals(1_234L, update.viewCount)
        assertEquals(true, update.isLive)
        assertEquals("Started streaming 2 hours ago", update.dateText)
        assertEquals(3_479_764L, update.likeCount)
        assertEquals("next-token", update.continuation)
        assertEquals(5_000L, update.timeoutMs)
    }

    @Test
    fun `an ended stream reads as not live and keeps its exact count`() {
        val update = Json.parseToJsonElement(ENDED).toLiveMetadataUpdate(hl = "en")

        assertEquals(false, update.isLive)
        assertEquals(651_847_120L, update.viewCount)
    }

    @Test
    fun `an answer with no viewership or continuation leaves both unknown`() {
        val update = Json.parseToJsonElement("""{"responseContext":{}}""").toLiveMetadataUpdate(hl = "en")

        assertNull(update.isLive)
        assertNull(update.viewCount)
        assertNull(update.continuation)
    }

    private companion object {
        const val LIVE = """
        {"actions":[
          {"updateViewershipAction":{"viewCount":{"videoViewCountRenderer":{
            "viewCount":{"runs":[{"text":"1,234"},{"text":" watching now"}]},"isLive":true,"originalViewCount":"0"}}}},
          {"updateDateTextAction":{"dateText":{"simpleText":"Started streaming 2 hours ago"}}},
          {"updateMerchandiseShelfAction":{"shelf":{}}}
        ],
        "continuation":{"timedContinuationData":{"timeoutMs":5000,"continuation":"next-token"}},
        "frameworkUpdates":{"entityBatchUpdate":{"mutations":[
          {"payload":{"featuredProductsEntity":{}}},
          {"payload":{"likeCountEntity":{"likeCountIfIndifferentNumber":"3479764"}}}
        ]}}}
        """

        const val ENDED = """
        {"actions":[{"updateViewershipAction":{"viewCount":{"videoViewCountRenderer":{
          "viewCount":{"simpleText":"651,847,120 views"},"originalViewCount":"651847120"}}}}],
        "continuation":{"timedContinuationData":{"timeoutMs":5000,"continuation":"c"}}}
        """
    }
}
