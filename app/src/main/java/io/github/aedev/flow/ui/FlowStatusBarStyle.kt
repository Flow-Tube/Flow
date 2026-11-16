package io.github.aedev.flow.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import io.github.aedev.flow.data.local.MusicPlayerBackgroundStyle
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.ui.components.musicplayer.common.canTurnLight
import io.github.aedev.flow.ui.components.musicplayer.sheet.MusicPlayerSheetState
import io.github.aedev.flow.ui.theme.ThemeMode
import io.github.aedev.flow.ui.theme.ThemeVariant
import io.github.aedev.flow.ui.theme.isEffectivelyDark

@Composable
internal fun ApplyStatusBarStyle(
    themeMode: ThemeMode,
    themeVariant: ThemeVariant,
    systemLightThemeMode: ThemeMode,
    systemDarkThemeMode: ThemeMode,
    isFullscreen: Boolean,
    preferences: PlayerPreferences,
    // Null while no song is loaded.
    musicPlayer: MusicPlayerSheetState? = null,
    isShortsPlayer: Boolean = false,
    // The expanded video player paints its black ground under the status bar on every theme.
    videoPlayerExpanded: Boolean = false,
    // A page's full-bleed hero is under the status bar, so its icons must be light to read on the photo.
    heroUnderStatusBar: Boolean = false,
) {
    val activity = LocalContext.current as? Activity ?: return
    val view = LocalView.current
    val colorScheme = MaterialTheme.colorScheme
    val musicBackgroundStyle by preferences.musicPlayerBackgroundStyle.collectAsState(initial = MusicPlayerBackgroundStyle.BLUR_GRADIENT)
    val musicFollowLightTheme by preferences.musicPlayerFollowLightTheme.collectAsState(initial = false)
    val isMusicPlayerImmersive = musicPlayer?.isImmersive == true
    // The lyrics page paints its own dark backdrop over the player, whatever the player's style.
    val musicPlayerOnThemeSurface =
        musicPlayer?.isLyricsShown == false &&
            (musicBackgroundStyle == MusicPlayerBackgroundStyle.DEFAULT || (musicFollowLightTheme && musicBackgroundStyle.canTurnLight))
    val isSystemDark = isSystemInDarkTheme()
    val isDarkTheme =
        themeMode.isEffectivelyDark(
            isSystemDark = isSystemDark,
            systemLightThemeMode = systemLightThemeMode,
            systemDarkThemeMode = systemDarkThemeMode,
            themeVariant = themeVariant,
        )

    SideEffect {
        val window = activity.window
        val insetsController = WindowCompat.getInsetsController(window, view)
        val shouldDrawBehindStatusBar =
            isFullscreen || isMusicPlayerImmersive || isShortsPlayer || videoPlayerExpanded || heroUnderStatusBar

        window.statusBarColor =
            if (shouldDrawBehindStatusBar) {
                android.graphics.Color.TRANSPARENT
            } else {
                colorScheme.background.toArgb()
            }

        val musicImmersiveOnLightSurface =
            isMusicPlayerImmersive && musicPlayerOnThemeSurface && !isDarkTheme && !isFullscreen && !isShortsPlayer
        insetsController.isAppearanceLightStatusBars =
            (!isDarkTheme && !shouldDrawBehindStatusBar) || musicImmersiveOnLightSurface
    }
}

/**
 * Lets the page on screen say its hero photo sits under the status bar, so the status bar icons
 * turn light while the photo is there. Only the page that claimed it last can release it, so a
 * page leaving during a transition never clears the claim of the page that replaced it.
 */
@Stable
internal class StatusBarHeroState {
    private var owner: Any? by mutableStateOf(null)

    val heroUnderStatusBar: Boolean
        get() = owner != null

    fun claim(owner: Any) {
        this.owner = owner
    }

    fun release(owner: Any) {
        if (this.owner === owner) this.owner = null
    }
}

internal val LocalStatusBarHero = staticCompositionLocalOf<StatusBarHeroState?> { null }
