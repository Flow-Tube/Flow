package io.github.aedev.flow.ui.components.shared

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.aedev.flow.R
import io.github.aedev.flow.innertube.pages.renderer.CommunityPost

/** Opens the share chooser with a community post's link. */
@Composable
fun rememberPostShareAction(): (CommunityPost) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { post: CommunityPost ->
            val shareIntent =
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "https://www.youtube.com/post/${post.id}")
                }
            context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.share_community_post)))
        }
    }
}
