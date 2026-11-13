package io.github.aedev.flow.ui.tv.input

import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TvPlayerKeyMapperTest {
    @Test
    fun `media keys map to playback actions`() {
        assertThat(TvPlayerKeyMapper.map(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            .isEqualTo(TvPlayerAction.TOGGLE_PLAYBACK)
        assertThat(TvPlayerKeyMapper.map(KeyEvent.KEYCODE_HEADSETHOOK))
            .isEqualTo(TvPlayerAction.TOGGLE_PLAYBACK)
        assertThat(TvPlayerKeyMapper.map(KeyEvent.KEYCODE_MEDIA_PLAY))
            .isEqualTo(TvPlayerAction.PLAY)
        assertThat(TvPlayerKeyMapper.map(KeyEvent.KEYCODE_MEDIA_PAUSE))
            .isEqualTo(TvPlayerAction.PAUSE)
        assertThat(TvPlayerKeyMapper.map(KeyEvent.KEYCODE_MEDIA_STOP))
            .isEqualTo(TvPlayerAction.PAUSE)
        assertThat(TvPlayerKeyMapper.map(KeyEvent.KEYCODE_MEDIA_REWIND))
            .isEqualTo(TvPlayerAction.SEEK_BACK)
        assertThat(TvPlayerKeyMapper.map(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
            .isEqualTo(TvPlayerAction.SEEK_FORWARD)
        assertThat(TvPlayerKeyMapper.map(KeyEvent.KEYCODE_MEDIA_NEXT))
            .isEqualTo(TvPlayerAction.NEXT)
        assertThat(TvPlayerKeyMapper.map(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
            .isEqualTo(TvPlayerAction.PREVIOUS)
        assertThat(TvPlayerKeyMapper.map(KeyEvent.KEYCODE_CAPTIONS))
            .isEqualTo(TvPlayerAction.TOGGLE_CAPTIONS)
    }

    @Test
    fun `space toggles playback`() {
        assertThat(TvPlayerKeyMapper.map(KeyEvent.KEYCODE_SPACE)).isEqualTo(TvPlayerAction.TOGGLE_PLAYBACK)
    }

    @Test
    fun `global map never consumes dpad navigation keys`() {
        listOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_TAB,
        ).forEach { keyCode ->
            assertThat(TvPlayerKeyMapper.map(keyCode)).isNull()
        }
    }

    @Test
    fun `hidden controls center keys toggle playback`() {
        assertThat(TvPlayerKeyMapper.mapDpadWhenControlsHidden(KeyEvent.KEYCODE_DPAD_CENTER))
            .isEqualTo(TvPlayerAction.TOGGLE_PLAYBACK)
        assertThat(TvPlayerKeyMapper.mapDpadWhenControlsHidden(KeyEvent.KEYCODE_ENTER))
            .isEqualTo(TvPlayerAction.TOGGLE_PLAYBACK)
        assertThat(TvPlayerKeyMapper.mapDpadWhenControlsHidden(KeyEvent.KEYCODE_NUMPAD_ENTER))
            .isEqualTo(TvPlayerAction.TOGGLE_PLAYBACK)
    }

    @Test
    fun `hidden controls down reveals transport`() {
        assertThat(TvPlayerKeyMapper.mapDpadWhenControlsHidden(KeyEvent.KEYCODE_DPAD_DOWN))
            .isEqualTo(TvPlayerAction.SHOW_TRANSPORT)
    }

    @Test
    fun `hidden controls start scrubs and open up next`() {
        assertThat(TvPlayerKeyMapper.mapDpadWhenControlsHidden(KeyEvent.KEYCODE_DPAD_LEFT))
            .isEqualTo(TvPlayerAction.SCRUB_BACK)
        assertThat(TvPlayerKeyMapper.mapDpadWhenControlsHidden(KeyEvent.KEYCODE_DPAD_RIGHT))
            .isEqualTo(TvPlayerAction.SCRUB_FORWARD)
        assertThat(TvPlayerKeyMapper.mapDpadWhenControlsHidden(KeyEvent.KEYCODE_DPAD_UP))
            .isEqualTo(TvPlayerAction.SHOW_UP_NEXT)
    }

    @Test
    fun `hidden controls ignore unrelated keys`() {
        assertThat(TvPlayerKeyMapper.mapDpadWhenControlsHidden(KeyEvent.KEYCODE_TAB)).isNull()
        assertThat(TvPlayerKeyMapper.mapDpadWhenControlsHidden(KeyEvent.KEYCODE_BACK)).isNull()
    }

    @Test
    fun `focused seek bar scrubs horizontally and commits on center`() {
        assertThat(TvPlayerKeyMapper.mapDpadWhenSeekBarFocused(KeyEvent.KEYCODE_DPAD_LEFT))
            .isEqualTo(TvPlayerAction.SCRUB_BACK)
        assertThat(TvPlayerKeyMapper.mapDpadWhenSeekBarFocused(KeyEvent.KEYCODE_DPAD_RIGHT))
            .isEqualTo(TvPlayerAction.SCRUB_FORWARD)
        assertThat(TvPlayerKeyMapper.mapDpadWhenSeekBarFocused(KeyEvent.KEYCODE_DPAD_CENTER))
            .isEqualTo(TvPlayerAction.COMMIT_SCRUB)
        assertThat(TvPlayerKeyMapper.mapDpadWhenSeekBarFocused(KeyEvent.KEYCODE_ENTER))
            .isEqualTo(TvPlayerAction.COMMIT_SCRUB)
    }

    @Test
    fun `focused seek bar leaves vertical navigation to compose focus`() {
        assertThat(TvPlayerKeyMapper.mapDpadWhenSeekBarFocused(KeyEvent.KEYCODE_DPAD_UP)).isNull()
        assertThat(TvPlayerKeyMapper.mapDpadWhenSeekBarFocused(KeyEvent.KEYCODE_DPAD_DOWN)).isNull()
    }
}
