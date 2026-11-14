package io.github.aedev.flow.data.contentfilter.aislist

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/** The pipeline, Flow Desktop and this app must agree on every handle, or nothing matches. */
class AiHandleTest {
    @Test
    fun `every shared vector normalizes exactly as the pipeline does`() {
        val vectors =
            Json
                .parseToJsonElement(javaClass.getResource("/aislist/normalize-vectors.json")!!.readText())
                .jsonArray
                .map { it.jsonObject }

        assertThat(vectors).hasSize(9)
        vectors.forEach { vector ->
            val input = vector.getValue("input").jsonPrimitive.content
            val expected = vector.getValue("normalized").jsonPrimitive.contentOrNull
            assertThat(AiHandle.normalize(input)).isEqualTo(expected)
        }
    }

    @Test
    fun `length is counted in code points, so an emoji counts once`() {
        val sixtyFour = "a".repeat(63) + "😀"

        assertThat(AiHandle.normalize("@$sixtyFour")).isEqualTo(sixtyFour)
        assertThat(AiHandle.normalize("@a$sixtyFour")).isNull()
    }

    @Test
    fun `whitespace follows JavaScript, not Char isWhitespace`() {
        assertThat(AiHandle.normalize(" @Name﻿")).isEqualTo("name")
        assertThat(AiHandle.normalize("@Na me")).isNull()
        assertThat(AiHandle.normalize("@Na\u0085me")).isEqualTo("na\u0085me")
        assertThat(AiHandle.normalize("@Na\u001Cme")).isEqualTo("na\u001Cme")
    }

    @Test
    fun `a plus sign survives percent decoding`() {
        assertThat(AiHandle.normalize("@a+b%2Bc")).isEqualTo("a+b+c")
    }

    @Test
    fun `a broken UTF-8 sequence keeps the input, which is then rejected for its percent sign`() {
        assertThat(AiHandle.normalize("@caf%C3")).isNull()
        assertThat(AiHandle.normalize("@caf%C3%A9")).isEqualTo("café")
    }

    @Test
    fun `a channel URL path gives its handle`() {
        assertThat(AiHandle.normalize("https://www.youtube.com/@SomeOne/videos?x=1")).isEqualTo("someone")
        assertThat(AiHandle.normalize("/@SomeOne")).isEqualTo("someone")
    }

    @Test
    fun `a report names the handle in its own case, decoded`() {
        assertThat(AiHandle.forReport("/@AweFolktales")).isEqualTo("@AweFolktales")
        assertThat(AiHandle.forReport("/@M%C3%BAsicaB%C3%ADblica")).isEqualTo("@MúsicaBíblica")
        assertThat(AiHandle.forReport("@소소한작업실-z8d")).isEqualTo("@소소한작업실-z8d")
    }

    @Test
    fun `a report refuses what YouTube would not take as a handle`() {
        assertThat(AiHandle.forReport("@ab")).isNull()
        assertThat(AiHandle.forReport("@" + "a".repeat(31))).isNull()
        assertThat(AiHandle.forReport("@has space")).isNull()
        assertThat(AiHandle.forReport("@emoji\uD83D\uDE00")).isNull()
    }

    @Test
    fun `AiSList answers map to what the viewer is told`() {
        assertThat(AiReportResult.fromStatus(200)).isEqualTo(AiReportResult.SENT)
        assertThat(AiReportResult.fromStatus(409)).isEqualTo(AiReportResult.BUSY)
        assertThat(AiReportResult.fromStatus(429)).isEqualTo(AiReportResult.RATE_LIMITED)
        assertThat(AiReportResult.fromStatus(400)).isEqualTo(AiReportResult.REJECTED)
        assertThat(AiReportResult.fromStatus(503)).isEqualTo(AiReportResult.FAILED)
    }
}
