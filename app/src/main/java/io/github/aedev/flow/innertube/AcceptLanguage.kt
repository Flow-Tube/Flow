package io.github.aedev.flow.innertube

/**
 * The Accept-Language a browser set to the content language [hl] sends, English last. A POST's
 * language comes from the `hl` in its body, but a GET has only this header to go by.
 */
internal fun acceptLanguage(hl: String): String {
    val language = hl.substringBefore('-')
    return when {
        hl == "en" -> "en-US,en;q=0.9"
        language == "en" || hl == language -> "$hl,en;q=0.9"
        else -> "$hl,$language;q=0.9,en;q=0.8"
    }
}
