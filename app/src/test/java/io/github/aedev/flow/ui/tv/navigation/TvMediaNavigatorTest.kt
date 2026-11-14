package io.github.aedev.flow.ui.tv.navigation

import android.net.Uri
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.ui.components.settings.SettingsDestination
import io.github.aedev.flow.ui.components.settings.SettingsTarget
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test

class TvMediaNavigatorTest {
    private val navController = mockk<NavHostController>(relaxed = true)
    private var closed = 0
    private val waiting = mutableListOf<String>()
    private val navigator =
        TvMediaNavigator(
            navController = navController,
            beforeNavigate = { closed++ },
            playVideo = {},
            navigateWhenReady = { waiting += it },
        )
    private val sync = SettingsTarget(SettingsDestination.SYNC)

    @Before
    fun setUp() {
        mockkStatic(Uri::class)
        every { Uri.encode(any()) } answers { firstArg() }
    }

    @After
    fun tearDown() = unmockkStatic(Uri::class)

    @Test
    fun `a settings page opens over the shell after closing the player`() {
        every { navController.currentBackStackEntry } returns mockk<NavBackStackEntry>(relaxed = true)
        val route = TvRoutes.settingsPage(sync)
        navigator.openSettings(sync)
        assertThat(closed).isEqualTo(1)
        verify { navController.navigate(route) }
        assertThat(waiting).isEmpty()
    }

    @Test
    fun `a settings page waits for a shell that has not set its graph`() {
        every { navController.currentBackStackEntry } returns null
        navigator.openSettings(sync)
        assertThat(waiting).containsExactly(TvRoutes.settingsPage(sync))
    }
}
