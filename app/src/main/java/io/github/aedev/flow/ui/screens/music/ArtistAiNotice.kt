package io.github.aedev.flow.ui.screens.music

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.shared.FlowNoticeBanner
import io.github.aedev.flow.ui.theme.Dimensions

/** Which AI list an opened artist is on, which decides the banner's wording. */
internal enum class ArtistAiNotice(
    val message: Int,
) {
    MUSIC_LIST(R.string.ai_music_artist_listed),
    CHANNEL_BLOCK_LIST(R.string.ai_list_channel_blocked),
    CHANNEL_WARN_LIST(R.string.ai_list_channel_warned),
}

/**
 * An opened artist page keeps its music; the banner says why it is hidden elsewhere, and "Show
 * their music" adds the artist to the allowlist shared with AI channels.
 */
internal fun LazyListScope.artistAiNotice(
    notice: ArtistAiNotice?,
    onShow: () -> Unit,
) {
    if (notice == null) return
    item(key = "ai_notice") {
        FlowNoticeBanner(
            icon = ImageVector.vectorResource(R.drawable.ic_ai_content),
            message = stringResource(notice.message),
            actionLabel = stringResource(R.string.ai_music_artist_show),
            onAction = onShow,
            modifier = Modifier.padding(horizontal = Dimensions.ContentPaddingHorizontal, vertical = 8.dp),
        )
    }
}
