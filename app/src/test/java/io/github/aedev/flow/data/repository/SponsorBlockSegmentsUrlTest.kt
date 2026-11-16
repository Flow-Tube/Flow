package io.github.aedev.flow.data.repository

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.SponsorBlockAction
import io.github.aedev.flow.data.model.SponsorBlockCategories
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class SponsorBlockSegmentsUrlTest {
    private val url = SponsorBlockRepository.segmentsUrl("dQw4w9WgXcQ")

    private fun jsonList(parameter: String): List<String> =
        Json
            .parseToJsonElement(requireNotNull(url.queryParameter(parameter)))
            .jsonArray
            .map { it.jsonPrimitive.content }

    @Test
    fun `the lookup asks for every category the settings offer`() {
        assertThat(jsonList("categories")).containsExactlyElementsIn(SponsorBlockCategories.all)
    }

    @Test
    fun `filler and preview are fetched`() {
        assertThat(jsonList("categories")).containsAtLeast("filler", "preview")
    }

    @Test
    fun `hooks and highlights are fetched but chapters are not`() {
        val categories = jsonList("categories")

        assertThat(categories).containsAtLeast("hook", "poi_highlight")
        assertThat(categories).doesNotContain("chapter")
    }

    @Test
    fun `the lookup asks for whole-video labels alongside skip and mute ranges`() {
        assertThat(jsonList("actionTypes")).containsExactly("skip", "mute", "full", "poi")
    }

    @Test
    fun `the video id is sent as is`() {
        assertThat(url.queryParameter("videoID")).isEqualTo("dQw4w9WgXcQ")
    }

    @Test
    fun `whole-video categories cannot be submitted as a range`() {
        assertThat(SponsorBlockCategories.submittable).doesNotContain(SponsorBlockCategories.EXCLUSIVE_ACCESS)
        assertThat(SponsorBlockCategories.submittable).contains(SponsorBlockCategories.FILLER)
    }

    @Test
    fun `only filler, preview, hook and highlight default to showing without skipping`() {
        val ignoredByDefault = SponsorBlockCategories.all.filter { SponsorBlockCategories.defaultAction(it) == SponsorBlockAction.IGNORE }

        assertThat(ignoredByDefault).containsExactly("filler", "preview", "hook", "poi_highlight")
    }
}
