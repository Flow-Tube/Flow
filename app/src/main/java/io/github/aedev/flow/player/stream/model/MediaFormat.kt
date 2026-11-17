package io.github.aedev.flow.player.stream.model

/** The container a googlevideo format comes in, as YouTube's `mimeType` names it. */
@Suppress("ktlint:standard:enum-entry-name-case", "EnumEntryName")
enum class MediaFormat(
    val mimeType: String,
) {
    MPEG_4("video/mp4"),
    v3GPP("video/3gpp"),
    WEBM("video/webm"),
    M4A("audio/mp4"),
    WEBMA("audio/webm"),
    WEBMA_OPUS("audio/webm"),
}
