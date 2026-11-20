package io.github.aedev.flow.innertube

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class VisitorDataMintTest {
    private val calls = mutableListOf<String>()

    private fun mint(
        visitorId: () -> String?,
        swJsData: () -> String? = { "CgsSwJsData%3D%3D" },
    ) = VisitorDataMint(
        visitorId = {
            calls += "visitor_id"
            visitorId()
        },
        swJsData = {
            calls += "sw.js_data"
            swJsData()
        },
    )

    @Test
    fun `visitor_id is asked first and sw_js_data is never fetched when it answers`() =
        runTest {
            assertEquals("CgtVisitorId%3D%3D", mint(visitorId = { "CgtVisitorId%3D%3D" }).mint())
            assertEquals(listOf("visitor_id"), calls)
        }

    @Test
    fun `a failed visitor_id falls back to sw_js_data`() =
        runTest {
            assertEquals("CgsSwJsData%3D%3D", mint(visitorId = { throw IOException("offline") }).mint())
            assertEquals(listOf("visitor_id", "sw.js_data"), calls)
        }

    @Test
    fun `a blank or malformed visitor_id answer falls back too`() =
        runTest {
            assertEquals("CgsSwJsData%3D%3D", mint(visitorId = { "" }).mint())
            assertEquals("CgsSwJsData%3D%3D", mint(visitorId = { "not-visitor-data" }).mint())
        }

    @Test
    fun `both failing is a failure, not a blank identity`() =
        runTest {
            val mint = mint(visitorId = { null }, swJsData = { throw IOException("offline") })
            assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { mint.mint() } }
        }
}
