package io.github.aedev.flow.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * The link colour the TV description panel still uses; the phone sheet passes the theme primary
 * instead, and the TV surface is outside the player refactor's scope.
 */
val DescriptionLinkBlue = Color(0xFF3EA6FF)

/** YouTube's brand red, for marks that stay red in every theme. */
val YouTubeRed = Color(0xFFFF0000)

private val NewBadgeOnDark = Color(0xFF3DDC84)
private val NewBadgeOnLight = Color(0xFF12782F)
private const val NEW_BADGE_DARK_SURFACE_LUMINANCE = 0.5f

/** The new-setting dot keeps one hue in every theme, so it never blends into the theme's accent (#1714). */
fun newBadgeColor(surface: Color): Color = if (surface.luminance() < NEW_BADGE_DARK_SURFACE_LUMINANCE) NewBadgeOnDark else NewBadgeOnLight
