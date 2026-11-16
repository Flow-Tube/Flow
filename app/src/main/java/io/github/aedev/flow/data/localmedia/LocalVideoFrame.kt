package io.github.aedev.flow.data.localmedia

private const val MEDIA_STORE_PREFIX = "content://media/"
private const val VIDEO_MEDIA_PATH = "/video/media/"
private const val FRAME_PERCENT = 1.0 / 3

/** Which still a video file on the device is drawn with, read by one retriever pass. */
data class LocalVideoFrame(
    val preferEmbeddedThumbnail: Boolean,
    val framePercent: Double,
)

/**
 * The still for a MediaStore video, or null for anything else. The frame at 0 µs is black for a video
 * that fades in, and a black frame still decodes as a success (#1766), so the file's own cover is
 * read first and otherwise the frame a third of the way in.
 */
fun localVideoFrameFor(model: String): LocalVideoFrame? =
    if (model.startsWith(MEDIA_STORE_PREFIX) && model.contains(VIDEO_MEDIA_PATH)) {
        LocalVideoFrame(preferEmbeddedThumbnail = true, framePercent = FRAME_PERCENT)
    } else {
        null
    }
