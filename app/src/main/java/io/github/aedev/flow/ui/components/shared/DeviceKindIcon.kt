package io.github.aedev.flow.ui.components.shared

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.TabletAndroid
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The icon for a linked device: by the [kind] it tells over the live channel ("phone", "tablet",
 * "tv", "desktop"), else by its [platform] until it has said, an Android device as a phone.
 */
fun deviceKindIcon(
    kind: String?,
    platform: String? = null,
): ImageVector =
    when (kind ?: if (platform == "android") "phone" else "desktop") {
        "tv" -> Icons.Outlined.Tv
        "tablet" -> Icons.Outlined.TabletAndroid
        "desktop" -> Icons.Outlined.Computer
        else -> Icons.Outlined.PhoneAndroid
    }
