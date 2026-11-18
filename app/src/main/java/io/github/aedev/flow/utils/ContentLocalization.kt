package io.github.aedev.flow.utils

import io.github.aedev.flow.data.local.CONTENT_LANGUAGE_FOLLOW_APP
import io.github.aedev.flow.innertube.models.YouTubeContentLanguages
import io.github.aedev.flow.innertube.models.YouTubeLocale
import io.github.aedev.flow.innertube.models.normalizeYouTubeHostLanguage
import java.util.Locale

/** Two-letter country for YouTube's `gl`, falling back to the device country and then to US. */
fun normalizeYouTubeCountry(region: String): String {
    val normalized = region.trim().uppercase(Locale.US)
    if (normalized.matches(COUNTRY_CODE)) return normalized

    return Locale
        .getDefault()
        .country
        .trim()
        .uppercase(Locale.US)
        .takeIf { it.matches(COUNTRY_CODE) }
        ?: "US"
}

/** The locale every InnerTube request carries: the content language, else the language the UI is shown in. */
fun contentYouTubeLocale(
    appliedAppLanguage: String,
    contentLanguage: String,
    region: String,
    deviceLocale: Locale,
): YouTubeLocale {
    val requested = if (contentLanguage == CONTENT_LANGUAGE_FOLLOW_APP) appliedAppLanguage else contentLanguage
    // A language picked from YouTube's own list is already an `hl` YouTube accepts, regional variant included.
    val hl = if (contentLanguage in YouTubeContentLanguages) contentLanguage else normalizeYouTubeHostLanguage(requested, deviceLocale)
    return YouTubeLocale(gl = normalizeYouTubeCountry(region), hl = hl)
}

private val COUNTRY_CODE = Regex("[A-Z]{2}")
