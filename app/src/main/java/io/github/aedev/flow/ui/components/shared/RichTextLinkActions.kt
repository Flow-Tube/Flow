package io.github.aedev.flow.ui.components.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import io.github.aedev.flow.utils.RichTextLinkActions

/**
 * Link actions that keep one identity across recompositions, so text built from them is not rebuilt
 * each time a caller passes a fresh lambda. Only a change in which kinds are handled rebuilds it.
 */
@Composable
fun rememberRichTextLinkActions(
    onSeekMs: ((Long) -> Unit)? = null,
    onOpenUrl: ((String) -> Unit)? = null,
    onChannel: ((String) -> Unit)? = null,
    onHashtag: ((String) -> Unit)? = null,
): RichTextLinkActions {
    val seek by rememberUpdatedState(onSeekMs)
    val open by rememberUpdatedState(onOpenUrl)
    val channel by rememberUpdatedState(onChannel)
    val hashtag by rememberUpdatedState(onHashtag)
    return remember(onSeekMs != null, onOpenUrl != null, onChannel != null, onHashtag != null) {
        RichTextLinkActions(
            onSeekMs = onSeekMs?.let { { ms: Long -> seek?.invoke(ms) } },
            onOpenUrl = onOpenUrl?.let { { url: String -> open?.invoke(url) } },
            onChannel = onChannel?.let { { id: String -> channel?.invoke(id) } },
            onHashtag = onHashtag?.let { { tag: String -> hashtag?.invoke(tag) } },
        )
    }
}
