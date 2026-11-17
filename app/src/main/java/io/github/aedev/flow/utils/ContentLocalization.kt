package io.github.aedev.flow.utils

import io.github.aedev.flow.data.local.CONTENT_LANGUAGE_FOLLOW_APP
import io.github.aedev.flow.innertube.models.YouTubeContentLanguages
import io.github.aedev.flow.innertube.models.YouTubeLocale
import io.github.aedev.flow.innertube.models.normalizeYouTubeHostLanguage
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.localization.TimeAgoPatternsManager
import java.util.Locale

private val FALLBACK_LOCALIZATION = Localization("en", "US")

/**
 * Content language for the NewPipe extraction path, resolved from the same app-language preference
 * that drives `YouTube.locale`. Pinning this to English made YouTube auto-translate titles into
 * English on every NewPipe-backed list while the player kept the original title (#844, #124).
 *
 * NewPipe throws for a localization it has no time-ago patterns for, so narrow the request the same
 * way `StreamingService.getTimeAgoParser` does before giving up on the user's language.
 */
fun newPipeLocalization(languageTag: String): Localization {
    val requested =
        Localization
            .fromLocalizationCode(normalizeYouTubeHostLanguage(languageTag))
            .orElse(null)
            ?: return FALLBACK_LOCALIZATION

    return requested.takeIf { it.hasTimeAgoPatterns() }
        ?: Localization(requested.languageCode).takeIf { it.hasTimeAgoPatterns() }
        ?: FALLBACK_LOCALIZATION
}

private fun Localization.hasTimeAgoPatterns(): Boolean = TimeAgoPatternsManager.getTimeAgoParserFor(this) != null

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

fun newPipeContentCountry(region: String): ContentCountry = ContentCountry(normalizeYouTubeCountry(region))

private val COUNTRY_CODE = Regex("[A-Z]{2}")
