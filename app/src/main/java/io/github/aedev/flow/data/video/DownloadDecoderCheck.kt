package io.github.aedev.flow.data.video

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import io.github.aedev.flow.innertube.models.response.PlayerResponse.StreamingData.Format
import io.github.aedev.flow.player.quality.VideoDecoderSupport
import io.github.aedev.flow.player.stream.VideoCodecUtils

/**
 * Whether this device plays a video format smoothly: the test playback's Auto uses. A download is
 * kept to formats that pass unless the user picks one that doesn't.
 */
@OptIn(UnstableApi::class)
internal fun Format.playsSmoothlyHere(): Boolean =
    VideoDecoderSupport.canDecode(
        codecs = VideoCodecUtils.codecStringFromMimeType(mimeType).ifEmpty { null },
        width = width ?: 0,
        height = height ?: 0,
        fps = fps ?: 0,
    )
