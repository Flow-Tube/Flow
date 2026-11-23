package io.github.aedev.flow.data.local

/**
 * A feed screen's stored Grid/List flag, or Home's layout while that screen's own toggle has never
 * been tapped, so every feed opens the way Home does until the user picks otherwise there.
 * [flagMeansList] says which way the screen's stored boolean points.
 */
internal fun screenLayoutFlag(
    stored: Boolean?,
    homeViewMode: String?,
    flagMeansList: Boolean,
): Boolean = stored ?: ((homeViewMode == HomeViewMode.LIST.name) == flagMeansList)
