package io.github.aedev.flow.innertube.pages.renderer

import io.github.aedev.flow.innertube.pages.arrayOrNull
import io.github.aedev.flow.innertube.pages.objectOrNull
import io.github.aedev.flow.innertube.pages.stringOrNull
import io.github.aedev.flow.innertube.pages.youtubeText
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal enum class LiveState { NONE, LIVE, UPCOMING }

/**
 * What a video item is, read in the order YouTube's own client reads it: the endpoint and content
 * type, then the style enums on its badges and overlays, then structured data. None of those are
 * translated. Works over a `lockupViewModel` and over the `videoRenderer` family alike, since the
 * keys each one reads never occur in the other.
 */
internal class ContentSignals private constructor(
    val isShort: Boolean,
    val liveState: LiveState,
    /** Epoch seconds. */
    val scheduledStart: Long?,
    val membersOnlyLabel: String?,
    private val hasStyledStatus: Boolean,
) {
    /**
     * The wording is consulted only when the item carries no styled status at all and the response is
     * English, since that is the only language the fallback phrases are written in.
     */
    fun isLive(
        hl: String?,
        englishFallback: () -> Boolean,
    ): Boolean = liveState == LiveState.LIVE || (!hasStyledStatus && hl.isEnglish() && englishFallback())

    companion object {
        fun of(renderer: JsonObject): ContentSignals {
            val thumbnailBadgeStyles = renderer.thumbnailBadgeStyles()
            val timeStatusStyles = renderer.timeStatusStyles()
            val metadataBadges = renderer.metadataBadges()
            val scheduledStart = renderer.scheduledStart()
            val liveState =
                when {
                    LOCKUP_LIVE_STYLE in thumbnailBadgeStyles ||
                        TIME_STATUS_LIVE in timeStatusStyles ||
                        metadataBadges.any { it.style == METADATA_LIVE_STYLE } -> LiveState.LIVE

                    TIME_STATUS_UPCOMING in timeStatusStyles || scheduledStart != null -> LiveState.UPCOMING

                    else -> LiveState.NONE
                }
            return ContentSignals(
                isShort =
                    renderer["contentType"].stringOrNull() in SHORT_CONTENT_TYPES ||
                        renderer.command().opensShorts() ||
                        TIME_STATUS_SHORTS in timeStatusStyles,
                liveState = liveState,
                scheduledStart = scheduledStart,
                membersOnlyLabel = metadataBadges.firstOrNull { it.style in MEMBERS_ONLY_STYLES }?.label,
                hasStyledStatus = thumbnailBadgeStyles.isNotEmpty() || timeStatusStyles.isNotEmpty(),
            )
        }
    }
}

private class MetadataBadge(
    val style: String,
    val label: String?,
)

private fun String?.isEnglish(): Boolean = this?.startsWith("en", ignoreCase = true) == true

/** A lockup's tap command, or a renderer's navigation endpoint. */
private fun JsonObject.command(): JsonObject? =
    this["navigationEndpoint"].objectOrNull()
        ?: this["rendererContext"]
            .objectOrNull()
            ?.get("commandContext")
            .objectOrNull()
            ?.get("onTap")
            .objectOrNull()
            ?.get("innertubeCommand")
            .objectOrNull()

private fun JsonObject?.opensShorts(): Boolean =
    this != null &&
        (
            this["reelWatchEndpoint"] != null ||
                this["commandMetadata"]
                    .objectOrNull()
                    ?.get("webCommandMetadata")
                    .objectOrNull()
                    ?.get("webPageType")
                    .stringOrNull() == WEB_PAGE_TYPE_SHORTS
        )

private fun JsonObject.thumbnailBadgeStyles(): List<String> =
    this["contentImage"]
        .objectOrNull()
        ?.get("thumbnailViewModel")
        .objectOrNull()
        ?.get("overlays")
        .arrayOrNull()
        .orEmpty()
        .flatMap { overlay ->
            val node = overlay.objectOrNull()
            node
                ?.get("thumbnailOverlayBadgeViewModel")
                .objectOrNull()
                ?.get("thumbnailBadges")
                .arrayOrNull()
                ?: node
                    ?.get("thumbnailBottomOverlayViewModel")
                    .objectOrNull()
                    ?.get("badges")
                    .arrayOrNull()
                    .orEmpty()
        }.mapNotNull {
            it
                .objectOrNull()
                ?.get("thumbnailBadgeViewModel")
                .objectOrNull()
                ?.get("badgeStyle")
                .stringOrNull()
                ?.takeIf(String::isNotBlank)
        }

private fun JsonObject.timeStatusStyles(): List<String> =
    this["thumbnailOverlays"].arrayOrNull().orEmpty().mapNotNull {
        it
            .objectOrNull()
            ?.get("thumbnailOverlayTimeStatusRenderer")
            .objectOrNull()
            ?.get("style")
            .stringOrNull()
            ?.takeIf(String::isNotBlank)
    }

/**
 * A renderer's `badges` hold `metadataBadgeRenderer`s; a lockup's badge view models sit among its
 * metadata rows instead.
 */
private fun JsonObject.metadataBadges(): List<MetadataBadge> {
    val found = mutableListOf<MetadataBadge>()
    this["badges"].arrayOrNull().orEmpty().forEach { badge ->
        badge.objectOrNull()?.get("metadataBadgeRenderer").objectOrNull()?.let { renderer ->
            val style = renderer["style"].stringOrNull() ?: return@let
            found += MetadataBadge(style, renderer["label"].stringOrNull()?.takeIf(String::isNotBlank))
        }
    }
    this["metadata"].forEachBadgeViewModel { found += it }
    return found
}

private fun JsonElement?.forEachBadgeViewModel(action: (MetadataBadge) -> Unit) {
    forEachObject { node ->
        val badge = node["badgeViewModel"].objectOrNull() ?: return@forEachObject
        val style = badge["badgeStyle"].stringOrNull() ?: return@forEachObject
        action(MetadataBadge(style, badge["badgeText"].youtubeText()?.takeIf(String::isNotBlank)))
    }
}

private fun JsonObject.scheduledStart(): Long? =
    this["upcomingEventData"]
        .objectOrNull()
        ?.get("startTime")
        .stringOrNull()
        ?.toLongOrNull()

private val SHORT_CONTENT_TYPES = setOf("LOCKUP_CONTENT_TYPE_SHORT", "LOCKUP_CONTENT_TYPE_SHORTS")
private val MEMBERS_ONLY_STYLES = setOf("BADGE_MEMBERS_ONLY", "BADGE_STYLE_TYPE_MEMBERS_ONLY")
private const val WEB_PAGE_TYPE_SHORTS = "WEB_PAGE_TYPE_SHORTS"
private const val LOCKUP_LIVE_STYLE = "THUMBNAIL_OVERLAY_BADGE_STYLE_LIVE"
private const val METADATA_LIVE_STYLE = "BADGE_STYLE_TYPE_LIVE_NOW"
private const val TIME_STATUS_LIVE = "LIVE"
private const val TIME_STATUS_UPCOMING = "UPCOMING"
private const val TIME_STATUS_SHORTS = "SHORTS"
