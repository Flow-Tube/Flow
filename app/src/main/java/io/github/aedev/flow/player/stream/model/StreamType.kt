package io.github.aedev.flow.player.stream.model

/** What the player response says the video is: `videoDetails.isLive` and `isLiveContent` read together. */
enum class StreamType {
    VIDEO_STREAM,
    LIVE_STREAM,
    POST_LIVE_STREAM,
}
