package io.github.aedev.flow.player.config

import io.github.aedev.flow.player.stream.VideoCodecUtils

object PlayerConfig {
    const val TAG = "EnhancedPlayerManager"

    val PREFERRED_VIDEO_MIME_TYPES = VideoCodecUtils.preferredVideoMimeTypes()

    // ===== Cache Configuration =====

    /** Maximum cache size in bytes (500 MB — default) */
    const val CACHE_SIZE_BYTES = 500L * 1024L * 1024L

    /** The video and Shorts cache folder; it held every player's data before songs got their own. */
    const val CACHE_DIR_NAME = "exoplayer"

    /** The song and animated artwork cache folder. */
    const val MUSIC_CACHE_DIR_NAME = "exoplayer_music"

    // ===== Buffer Configuration =====

    /** Allocator buffer size (64 KB optimal for DASH segments) */
    const val ALLOCATOR_BUFFER_SIZE = 64 * 1024

    /** Back buffer duration in milliseconds (10 seconds for instant rewind) */
    const val BACK_BUFFER_DURATION_MS = 10_000

    /** Back buffer duration for low-memory devices. */
    const val LOW_MEMORY_BACK_BUFFER_DURATION_MS = 0

    /** The longest max buffer the main player takes, the top of the Custom slider. Bytes stop it sooner. */
    const val MAX_SAFE_MAIN_BUFFER_MS = 180_000

    /** Hard runtime cap for main-player min buffer so low-RAM devices do not over-retain media. */
    const val MAX_SAFE_MAIN_MIN_BUFFER_MS = 20_000

    /** Max buffer cap on a device Android flags as low-RAM. */
    const val LOW_MEMORY_MAX_SAFE_MAIN_BUFFER_MS = 18_000

    /** Min buffer cap when Android reports a small app heap. */
    const val LOW_MEMORY_MAX_SAFE_MAIN_MIN_BUFFER_MS = 8_000

    /** App heaps below this get the smallest video buffer; 256 MB is what most phones report. */
    const val SMALL_HEAP_MB = 192

    const val MAIN_TARGET_BUFFER_BYTES = 32 * 1024 * 1024

    /** Target buffer budgets for heaps under 192 MB (or low-RAM devices) and for 192-384 MB heaps. */
    const val LOW_MEMORY_MAIN_TARGET_BUFFER_BYTES = 4 * 1024 * 1024
    const val MID_MEMORY_MAIN_TARGET_BUFFER_BYTES = 12 * 1024 * 1024

    /**
     * A max buffer longer than the stock profiles may hold up to this share of the app heap, since
     * Media3's allocator keeps buffered media in heap arrays: 1/5 of a 256 MB heap is about 51 MB.
     */
    const val LONG_BUFFER_HEAP_DIVISOR = 5

    /** On a heap under [SMALL_HEAP_MB] a long buffer gets a tenth, about 13 MB of a 128 MB TV's heap (#2204). */
    const val SMALL_HEAP_LONG_BUFFER_HEAP_DIVISOR = 10
    const val MAX_LONG_BUFFER_BYTES = 128 * 1024 * 1024

    /** Explicit target buffer budget per shorts player in the pooled shorts stack. */
    const val SHORTS_TARGET_BUFFER_BYTES = 4 * 1024 * 1024

    /**
     * Shorts buffer window. Deliberately below the floors in `BufferDurations` — a swipe has to
     * start the next clip immediately, and three pooled players each holding a long window would
     * cost more memory than the feed is worth.
     */
    const val SHORTS_MIN_BUFFER_MS = 1_500
    const val SHORTS_MAX_BUFFER_MS = 8_000
    const val SHORTS_BUFFER_FOR_PLAYBACK_MS = 250
    const val SHORTS_BUFFER_FOR_REBUFFER_MS = 750
    const val SHORTS_BACK_BUFFER_MS = 2_000

    /**
     * Music buffer window. [MUSIC_MAX_BUFFER_MS] is the lean window (crossfade tails, small heaps
     * and Data Saver); otherwise a song loads whole and what has played stays, so a seek is in memory (#2167).
     */
    const val MUSIC_MIN_BUFFER_MS = 2_500
    const val MUSIC_MAX_BUFFER_MS = 30_000
    const val MUSIC_BUFFER_FOR_PLAYBACK_MS = 1_000
    const val MUSIC_BUFFER_FOR_REBUFFER_MS = 1_500
    const val MUSIC_WHOLE_SONG_BUFFER_MS = 10 * 60 * 1000
    const val MUSIC_BACK_BUFFER_MS = 10 * 60 * 1000

    /** Bounds a long mix and the music player's video mode, which would otherwise size the cap for video. */
    const val MUSIC_TARGET_BUFFER_BYTES = 24 * 1024 * 1024

    /** Preferred delay from the true live edge. Keeps YouTube live playback stable. */
    const val LIVE_EDGE_GAP_MS = 10_000L

    // ===== Bandwidth Thresholds =====

    /** Initial bandwidth estimate in bits per second (5 Mbps) */
    const val INITIAL_BANDWIDTH_ESTIMATE = 5_000_000L

    // ===== Quality Adaptation =====

    /** Share of the bandwidth estimate a stream may use, for Auto's own picks and Media3's ladder alike. */
    const val AUTO_BANDWIDTH_FRACTION = 0.7f

    /** Buffer a ladder needs before Media3 moves it up a quality (Media3's default is 10 s). */
    const val ABR_MIN_BUFFER_FOR_QUALITY_INCREASE_MS = 5_000

    /**
     * Media3 holds the current quality while more than this is buffered. At its 25 s default a full
     * 45 s buffer drains for 20 s before a collapsed network steps down; the midpoint of the 20 s to
     * 45 s window it was tuned on cuts that to 12.5 s.
     */
    const val ABR_MAX_BUFFER_FOR_QUALITY_DECREASE_MS = 32_500

    /** Interval for bandwidth checks during playback (5 seconds) */
    const val BANDWIDTH_CHECK_INTERVAL_MS = 5000L

    /** Upgrade threshold - need 30% more bandwidth to upgrade */
    const val QUALITY_UPGRADE_THRESHOLD = 1.3f

    /** Downgrade threshold - downgrade if bandwidth drops to 70% */
    const val QUALITY_DOWNGRADE_THRESHOLD = 0.7f

    /** Maximum stream errors before quality downgrade */
    const val MAX_STREAM_ERRORS = 2

    /** Buffering count threshold before quality downgrade */
    const val BUFFERING_COUNT_THRESHOLD = 3

    // ===== Position Tracking =====

    /** Position tracker polling interval in milliseconds */
    const val POSITION_TRACKER_INTERVAL_MS = 1_000L

    /** Auto-save interval in milliseconds (30 seconds) */
    const val AUTO_SAVE_INTERVAL_MS = 30_000L

    /** Stuck detection threshold (number of checks with no position change) */
    const val STUCK_DETECTION_THRESHOLD = 2

    // ===== Error Recovery =====

    /** Delay before retry after error in milliseconds */
    const val ERROR_RETRY_DELAY_MS = 1000L

    // ===== Renderer Scheduling (experiment) =====

    const val ENABLE_DYNAMIC_SCHEDULING = false

    // ===== Video Size Constraints =====
    const val MAX_VIDEO_WIDTH = 3840

    const val MAX_VIDEO_HEIGHT = 2160
}
