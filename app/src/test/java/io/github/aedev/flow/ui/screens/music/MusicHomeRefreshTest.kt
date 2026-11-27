package io.github.aedev.flow.ui.screens.music

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MusicHomeRefreshTest {
    @Test
    fun `a refresh is not settled until both fetches finish`() {
        val homeOnly = MusicHomeRefresh().settle(MusicRefreshLeg.HOME, ok = true)

        assertThat(MusicHomeRefresh().isSettled).isFalse()
        assertThat(homeOnly.isSettled).isFalse()
        assertThat(homeOnly.isFailed).isFalse()
    }

    @Test
    fun `both fetches finishing settles the refresh in either order`() {
        val homeFirst = MusicHomeRefresh().settle(MusicRefreshLeg.HOME, ok = true).settle(MusicRefreshLeg.CHARTS, ok = true)
        val chartsFirst = MusicHomeRefresh().settle(MusicRefreshLeg.CHARTS, ok = true).settle(MusicRefreshLeg.HOME, ok = true)

        assertThat(homeFirst.isSettled).isTrue()
        assertThat(chartsFirst.isSettled).isTrue()
        assertThat(homeFirst.isFailed).isFalse()
    }

    @Test
    fun `one successful fetch is enough for the refresh to count`() {
        val chartsOnly = MusicHomeRefresh().settle(MusicRefreshLeg.HOME, ok = false).settle(MusicRefreshLeg.CHARTS, ok = true)
        val homeOnly = MusicHomeRefresh().settle(MusicRefreshLeg.HOME, ok = true).settle(MusicRefreshLeg.CHARTS, ok = false)

        assertThat(chartsOnly.isFailed).isFalse()
        assertThat(homeOnly.isFailed).isFalse()
    }

    @Test
    fun `both fetches failing fails the refresh`() {
        val refresh = MusicHomeRefresh().settle(MusicRefreshLeg.HOME, ok = false).settle(MusicRefreshLeg.CHARTS, ok = false)

        assertThat(refresh.isSettled).isTrue()
        assertThat(refresh.isFailed).isTrue()
    }

    @Test
    fun `a failure stays a failure until both fetches have answered`() {
        val homeFailed = MusicHomeRefresh().settle(MusicRefreshLeg.HOME, ok = false)

        assertThat(homeFailed.isFailed).isFalse()
    }
}
