package io.github.aedev.flow.innertube.pages

import io.github.aedev.flow.data.model.RichText
import io.github.aedev.flow.data.model.RichTextTarget
import kotlinx.serialization.json.JsonObject

/** A song the video uses, as the description's Music section credits it. */
data class VideoDescriptionSong(
    val title: String,
    val artist: String,
    val album: String? = null,
    val thumbnailUrl: String? = null,
    /** Where a tap goes, when the card names a destination at all. */
    val target: RichTextTarget? = null,
)

/** The Music section: its title as the server words it, and the songs it lists. */
data class VideoDescriptionMusic(
    val title: String?,
    val songs: List<VideoDescriptionSong>,
)

/** The "How this was made" section: an altered, synthetic or auto-dubbed content disclosure. */
data class VideoDescriptionDisclosure(
    val title: String?,
    val header: String?,
    val body: RichText?,
)

/** The panel's card list of attributed items, which a music video fills with its songs, under the server's own title. */
internal fun JsonObject.toMusicSection(ownVideoId: String?): VideoDescriptionMusic? {
    val list = this["horizontalCardListRenderer"].objectOrNull() ?: return null
    val songs =
        list["cards"]
            .arrayOrNull()
            .orEmpty()
            .mapNotNull {
                it
                    .objectOrNull()
                    ?.get("videoAttributeViewModel")
                    .objectOrNull()
                    ?.toSong(ownVideoId)
            }
    if (songs.isEmpty()) return null
    val header = list["header"].objectOrNull()?.get("richListHeaderRenderer").objectOrNull()
    return VideoDescriptionMusic(
        title = header?.get("title").youtubeText()?.takeIf { it.isNotBlank() },
        songs = songs,
    )
}

private fun JsonObject.toSong(ownVideoId: String?): VideoDescriptionSong? {
    val title = this["title"].youtubeText()?.takeIf { it.isNotBlank() } ?: return null
    return VideoDescriptionSong(
        title = title,
        artist = this["subtitle"].youtubeText().orEmpty(),
        album = this["secondarySubtitle"].youtubeText()?.takeIf { it.isNotBlank() },
        thumbnailUrl =
            this["image"]
                .bestThumbnailUrl()
                ?.takeIf { it.isNotBlank() }
                ?.let(::normalizeImageUrl),
        target =
            this["onTap"]
                .objectOrNull()
                ?.get("innertubeCommand")
                .objectOrNull()
                ?.toRichTextTarget(ownVideoId),
    )
}

internal fun JsonObject.toDisclosure(ownVideoId: String?): VideoDescriptionDisclosure? {
    val section = this["howThisWasMadeSectionViewModel"].objectOrNull() ?: return null
    val header = section["bodyHeader"].youtubeText()?.takeIf { it.isNotBlank() }
    val body = section["bodyText"].toRichText(ownVideoId)?.takeIf { it.text.isNotBlank() }
    if (header == null && body == null) return null
    return VideoDescriptionDisclosure(
        title = section["sectionTitle"].youtubeText()?.takeIf { it.isNotBlank() },
        header = header,
        body = body,
    )
}
