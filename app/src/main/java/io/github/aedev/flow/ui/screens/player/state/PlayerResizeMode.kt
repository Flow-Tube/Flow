package io.github.aedev.flow.ui.screens.player.state

/** Media3 `AspectRatioFrameLayout` resize modes, in the order the fullscreen button cycles them. */
internal object PlayerResizeMode {
    const val FIT = 0
    const val FILL = 1
    const val ZOOM = 2
    private const val COUNT = 3

    fun next(mode: Int): Int = (mode + 1) % COUNT
}

/**
 * The resize mode the surface draws with. Only fullscreen takes the button's choice ([override]);
 * the page player and the 360 view always fit.
 */
internal fun effectiveResizeMode(
    isFullscreen: Boolean,
    spherical: Boolean,
    override: Int?,
): Int =
    when {
        !isFullscreen || spherical -> PlayerResizeMode.FIT
        else -> override ?: PlayerResizeMode.FIT
    }
