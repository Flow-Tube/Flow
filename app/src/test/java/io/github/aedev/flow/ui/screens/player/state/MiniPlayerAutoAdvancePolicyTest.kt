package io.github.aedev.flow.ui.screens.player.state

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pins where the sheet goes for a new video: an automatic advance in the mini player stays there. */
class MiniPlayerAutoAdvancePolicyTest {
    @Test
    fun `an automatic advance in the mini player stays collapsed`() {
        assertThat(newVideo(autoAdvanced = true, collapsed = true)).isEqualTo(SheetOnNewVideo.COLLAPSE)
        assertThat(load(autoAdvanced = true, collapsed = true)).isFalse()
    }

    @Test
    fun `an automatic advance in the full player stays expanded`() {
        assertThat(newVideo(autoAdvanced = false, collapsed = false, wasExpanded = true))
            .isEqualTo(SheetOnNewVideo.EXPAND)
        assertThat(load(autoAdvanced = false, collapsed = false)).isTrue()
    }

    @Test
    fun `a sheet opened after the advance is not pulled back down`() {
        assertThat(newVideo(autoAdvanced = true, collapsed = false, wasExpanded = true))
            .isEqualTo(SheetOnNewVideo.EXPAND)
        assertThat(load(autoAdvanced = true, collapsed = false)).isTrue()
    }

    @Test
    fun `a video the viewer picked opens the full player`() {
        assertThat(newVideo(autoAdvanced = false, collapsed = true)).isEqualTo(SheetOnNewVideo.OPEN)
        assertThat(load(autoAdvanced = false, collapsed = true)).isTrue()
    }

    @Test
    fun `a restored or resumed session stays in the mini player`() {
        assertThat(newVideo(restored = true, collapsed = false)).isEqualTo(SheetOnNewVideo.COLLAPSE)
        assertThat(newVideo(resumed = true, collapsed = false)).isEqualTo(SheetOnNewVideo.COLLAPSE)
        assertThat(load(restored = true)).isFalse()
        assertThat(load(resumed = true)).isFalse()
    }

    @Test
    fun `a finished load never expands`() {
        assertThat(load(isLoading = false)).isFalse()
    }

    private fun newVideo(
        autoAdvanced: Boolean = false,
        collapsed: Boolean = true,
        wasExpanded: Boolean = false,
        restored: Boolean = false,
        resumed: Boolean = false,
    ) = sheetOnNewVideo(
        isRestoredSession = restored,
        resumedInMiniPlayer = resumed,
        autoAdvancedInMiniPlayer = autoAdvanced,
        isCollapsed = collapsed,
        wasExpanded = wasExpanded,
    )

    private fun load(
        isLoading: Boolean = true,
        autoAdvanced: Boolean = false,
        collapsed: Boolean = true,
        restored: Boolean = false,
        resumed: Boolean = false,
    ) = expandsOnLoad(
        isLoading = isLoading,
        isRestoredSession = restored,
        resumedInMiniPlayer = resumed,
        autoAdvancedInMiniPlayer = autoAdvanced,
        isCollapsed = collapsed,
    )
}
