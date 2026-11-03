package io.github.aedev.flow.data.local

data class PlayerOverlayPreferences(
    val castEnabled: Boolean = true,
    val captionsEnabled: Boolean = false,
    val pipEnabled: Boolean = false,
    val autoplayEnabled: Boolean = false,
    val sleepTimerEnabled: Boolean = true,
    val speedIndicatorEnabled: Boolean = false,
    val commentsEnabled: Boolean = true,
    /** Shows only while SponsorBlock is on and the video has segments, so it costs nothing by default. */
    val sponsorBlockEnabled: Boolean = true,
    val nextPrevButtonsEnabled: Boolean = true,
    val showControlsWhileLoading: Boolean = false,
    val fullscreenSeekbarHorizontalPaddingDp: Int =
        resolveSeekbarHorizontalPaddingDp(
            mode = SeekbarPaddingMode.DEFAULT,
            customPaddingDp = DEFAULT_FULLSCREEN_SEEKBAR_PADDING_DP,
            defaultPaddingDp = DEFAULT_FULLSCREEN_SEEKBAR_PADDING_DP,
            maxPaddingDp = MAX_FULLSCREEN_SEEKBAR_PADDING_DP,
        ),
    val portraitSeekbarHorizontalPaddingDp: Int =
        resolveSeekbarHorizontalPaddingDp(
            mode = resolvePortraitSeekbarPaddingMode(null),
            customPaddingDp = DEFAULT_PORTRAIT_SEEKBAR_PADDING_DP,
            defaultPaddingDp = DEFAULT_PORTRAIT_SEEKBAR_PADDING_DP,
            maxPaddingDp = MAX_PORTRAIT_SEEKBAR_PADDING_DP,
        ),
    val idleProgressBar: IdleProgressBar = IdleProgressBar.ON_VIDEO,
    val scrubPreviewStyle: ScrubPreviewStyle = ScrubPreviewStyle.STRIP,
    /** Off by default: two more buttons in the transport row is a cost every video pays. */
    val frameStepButtonsEnabled: Boolean = false,
    /** Per-category ARGB overrides from SponsorBlock settings; absent categories use the defaults. */
    val sponsorCategoryColors: Map<String, Int> = emptyMap(),
)
