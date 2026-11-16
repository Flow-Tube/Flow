package io.github.aedev.flow.player

import androidx.media3.common.C
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackAdvanceCompletionTest {
    @Test
    fun `moving on from the end of a video records its duration`() {
        assertThat(PlaybackResumePolicy.finishedDurationOnAdvance(188_000L, 188_009L)).isEqualTo(188_000L)
        assertThat(PlaybackResumePolicy.finishedDurationOnAdvance(188_000L, 187_000L)).isEqualTo(188_000L)
    }

    @Test
    fun `moving on from the middle records nothing`() {
        assertThat(PlaybackResumePolicy.finishedDurationOnAdvance(188_000L, 40_818L)).isNull()
    }

    @Test
    fun `an unknown duration records nothing`() {
        assertThat(PlaybackResumePolicy.finishedDurationOnAdvance(C.TIME_UNSET, 188_000L)).isNull()
        assertThat(PlaybackResumePolicy.finishedDurationOnAdvance(0L, 188_000L)).isNull()
    }
}
