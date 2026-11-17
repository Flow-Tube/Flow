package io.github.aedev.flow.player.stream.model

import io.github.aedev.flow.player.stream.model.ItagInfo.ItagType

/**
 * YouTube's fixed itag assignments: container, kind, and the resolution and frame rate an itag
 * stands for. A `/player` format omits its frame rate now and then, and this is where the 30 or 60
 * then comes from; everything else is read from the response itself.
 *
 * Itags missing here (the 608x1080 Shorts tier, HDR VP9, newer audio) are described from the
 * response alone by the stream bridge.
 */
internal object KnownItags {
    class Known(
        val itag: Int,
        val type: ItagType,
        val format: MediaFormat,
        val resolution: String?,
        val fps: Int,
    )

    val byItag: Map<Int, Known> =
        listOf(
            Known(17, ItagType.VIDEO, MediaFormat.v3GPP, "144p", 30),
            Known(36, ItagType.VIDEO, MediaFormat.v3GPP, "240p", 30),
            Known(18, ItagType.VIDEO, MediaFormat.MPEG_4, "360p", 30),
            Known(34, ItagType.VIDEO, MediaFormat.MPEG_4, "360p", 30),
            Known(35, ItagType.VIDEO, MediaFormat.MPEG_4, "480p", 30),
            Known(59, ItagType.VIDEO, MediaFormat.MPEG_4, "480p", 30),
            Known(78, ItagType.VIDEO, MediaFormat.MPEG_4, "480p", 30),
            Known(22, ItagType.VIDEO, MediaFormat.MPEG_4, "720p", 30),
            Known(37, ItagType.VIDEO, MediaFormat.MPEG_4, "1080p", 30),
            Known(38, ItagType.VIDEO, MediaFormat.MPEG_4, "1080p", 30),
            Known(43, ItagType.VIDEO, MediaFormat.WEBM, "360p", 30),
            Known(44, ItagType.VIDEO, MediaFormat.WEBM, "480p", 30),
            Known(45, ItagType.VIDEO, MediaFormat.WEBM, "720p", 30),
            Known(46, ItagType.VIDEO, MediaFormat.WEBM, "1080p", 30),
            Known(171, ItagType.AUDIO, MediaFormat.WEBMA, null, -1),
            Known(172, ItagType.AUDIO, MediaFormat.WEBMA, null, -1),
            Known(599, ItagType.AUDIO, MediaFormat.M4A, null, -1),
            Known(139, ItagType.AUDIO, MediaFormat.M4A, null, -1),
            Known(140, ItagType.AUDIO, MediaFormat.M4A, null, -1),
            Known(141, ItagType.AUDIO, MediaFormat.M4A, null, -1),
            Known(600, ItagType.AUDIO, MediaFormat.WEBMA_OPUS, null, -1),
            Known(249, ItagType.AUDIO, MediaFormat.WEBMA_OPUS, null, -1),
            Known(250, ItagType.AUDIO, MediaFormat.WEBMA_OPUS, null, -1),
            Known(251, ItagType.AUDIO, MediaFormat.WEBMA_OPUS, null, -1),
            Known(160, ItagType.VIDEO_ONLY, MediaFormat.MPEG_4, "144p", 30),
            Known(394, ItagType.VIDEO_ONLY, MediaFormat.MPEG_4, "144p", 30),
            Known(133, ItagType.VIDEO_ONLY, MediaFormat.MPEG_4, "240p", 30),
            Known(395, ItagType.VIDEO_ONLY, MediaFormat.MPEG_4, "240p", 30),
            Known(134, ItagType.VIDEO_ONLY, MediaFormat.MPEG_4, "360p", 30),
            Known(396, ItagType.VIDEO_ONLY, MediaFormat.MPEG_4, "360p", 30),
            Known(135, ItagType.VIDEO_ONLY, MediaFormat.MPEG_4, "480p", 30),
            Known(212, ItagType.VIDEO_ONLY, MediaFormat.MPEG_4, "480p", 30),
            Known(397, ItagType.VIDEO_ONLY, MediaFormat.MPEG_4, "480p", 30),
            Known(136, ItagType.VIDEO_ONLY, MediaFormat.MPEG_4, "720p", 30),
            Known(398, ItagType.VIDEO_ONLY, MediaFormat.MPEG_4, "720p", 30),
            Known(298, ItagType.VIDEO_ONLY, MediaFormat.MPEG_4, "720p60", 60),
            Known(137, ItagType.VIDEO_ONLY, MediaFormat.MPEG_4, "1080p", 30),
            Known(399, ItagType.VIDEO_ONLY, MediaFormat.MPEG_4, "1080p", 30),
            Known(299, ItagType.VIDEO_ONLY, MediaFormat.MPEG_4, "1080p60", 60),
            Known(400, ItagType.VIDEO_ONLY, MediaFormat.MPEG_4, "1440p", 30),
            Known(266, ItagType.VIDEO_ONLY, MediaFormat.MPEG_4, "2160p", 30),
            Known(401, ItagType.VIDEO_ONLY, MediaFormat.MPEG_4, "2160p", 30),
            Known(278, ItagType.VIDEO_ONLY, MediaFormat.WEBM, "144p", 30),
            Known(242, ItagType.VIDEO_ONLY, MediaFormat.WEBM, "240p", 30),
            Known(243, ItagType.VIDEO_ONLY, MediaFormat.WEBM, "360p", 30),
            Known(244, ItagType.VIDEO_ONLY, MediaFormat.WEBM, "480p", 30),
            Known(245, ItagType.VIDEO_ONLY, MediaFormat.WEBM, "480p", 30),
            Known(246, ItagType.VIDEO_ONLY, MediaFormat.WEBM, "480p", 30),
            Known(247, ItagType.VIDEO_ONLY, MediaFormat.WEBM, "720p", 30),
            Known(248, ItagType.VIDEO_ONLY, MediaFormat.WEBM, "1080p", 30),
            Known(271, ItagType.VIDEO_ONLY, MediaFormat.WEBM, "1440p", 30),
            Known(272, ItagType.VIDEO_ONLY, MediaFormat.WEBM, "2160p", 30),
            Known(302, ItagType.VIDEO_ONLY, MediaFormat.WEBM, "720p60", 60),
            Known(303, ItagType.VIDEO_ONLY, MediaFormat.WEBM, "1080p60", 60),
            Known(308, ItagType.VIDEO_ONLY, MediaFormat.WEBM, "1440p60", 60),
            Known(313, ItagType.VIDEO_ONLY, MediaFormat.WEBM, "2160p", 30),
            Known(315, ItagType.VIDEO_ONLY, MediaFormat.WEBM, "2160p60", 60),
        ).associateBy { it.itag }
}
