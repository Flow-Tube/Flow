package io.github.aedev.flow.innertube.pages

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import io.github.aedev.flow.data.model.RichText
import io.github.aedev.flow.data.model.RichTextStyle
import io.github.aedev.flow.utils.toAnnotatedString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class RichTextStylesTest {
    private fun stylesOf(
        content: String,
        vararg runs: String,
    ): List<RichTextStyle> {
        val json = """{"content": "$content", "styleRuns": [${runs.joinToString()}]}"""
        return requireNotNull(Json.parseToJsonElement(json).toRichText(ownVideoId = null)).styles
    }

    @Test
    fun `medium weight and heavier read as bold`() {
        assertEquals(
            listOf(RichTextStyle(0, 4, bold = true), RichTextStyle(5, 4, bold = true)),
            stylesOf(
                "bold also plain",
                """{"startIndex": 0, "length": 4, "weightLabel": "FONT_WEIGHT_MEDIUM"}""",
                """{"startIndex": 5, "length": 4, "weightLabel": "FONT_WEIGHT_BOLD"}""",
                """{"startIndex": 10, "length": 5, "weightLabel": "FONT_WEIGHT_NORMAL"}""",
            ),
        )
    }

    @Test
    fun `italic and strike-through are kept`() {
        assertEquals(
            listOf(
                RichTextStyle(0, 5, italic = true),
                RichTextStyle(6, 6, strikethrough = true),
                RichTextStyle(13, 4, strikethrough = true),
            ),
            stylesOf(
                "slant struck gone",
                """{"startIndex": 0, "length": 5, "italic": true}""",
                """{"startIndex": 6, "length": 6, "strikethrough": "LINE_STYLE_SINGLE"}""",
                """{"startIndex": 13, "length": 4, "strikethrough": true}""",
            ),
        )
    }

    @Test
    fun `colour and font runs carry no style`() {
        assertEquals(
            emptyList<RichTextStyle>(),
            stylesOf(
                "link text",
                """{"startIndex": 0, "length": 4, "fontColor": 4279440147, "fontFamilyName": "Roboto"}""",
                """{"startIndex": 5, "length": 4, "italic": false, "strikethrough": "LINE_STYLE_NONE"}""",
            ),
        )
    }

    @Test
    fun `a run without a range covers the whole text and one past the end is dropped`() {
        assertEquals(
            listOf(RichTextStyle(0, 5, bold = true)),
            stylesOf(
                "title",
                """{"weightLabel": "FONT_WEIGHT_BOLD"}""",
                """{"startIndex": 3, "length": 9, "italic": true}""",
            ),
        )
    }

    @Test
    fun `styles render in the theme's weight, slant and decoration`() {
        val rendered =
            RichText(
                text = "bold slant struck",
                styles =
                    listOf(
                        RichTextStyle(0, 4, bold = true),
                        RichTextStyle(5, 5, italic = true),
                        RichTextStyle(11, 6, strikethrough = true),
                    ),
            ).toAnnotatedString(linkColor = Color.Blue, textColor = Color.Black)

        val styled = rendered.spanStyles.filter { it.item.color == Color.Unspecified }
        assertEquals(listOf(0 to 4, 5 to 10, 11 to 17), styled.map { it.start to it.end })
        assertEquals(FontWeight.Bold, styled[0].item.fontWeight)
        assertEquals(FontStyle.Italic, styled[1].item.fontStyle)
        assertEquals(TextDecoration.LineThrough, styled[2].item.textDecoration)
    }
}
