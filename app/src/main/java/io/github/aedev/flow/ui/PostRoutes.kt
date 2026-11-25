package io.github.aedev.flow.ui

import android.net.Uri
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import io.github.aedev.flow.ui.components.layout.navigation.MediaNavigator
import io.github.aedev.flow.ui.screens.post.POST_ROUTE_ARG
import io.github.aedev.flow.ui.screens.post.PostScreen
import io.github.aedev.flow.utils.YouTubeLink

internal const val POST_ROUTE_PATTERN = "post/{$POST_ROUTE_ARG}"

internal fun communityPostRoute(postId: String): String = "post/${Uri.encode(postId)}"

/** A community post opened on its own, from a `/post/` link. */
internal fun NavGraphBuilder.postRoutes(
    navController: NavHostController,
    mediaNavigator: MediaNavigator,
) {
    page(POST_ROUTE_PATTERN, arguments = listOf(navArgument(POST_ROUTE_ARG) { type = NavType.StringType })) {
        PostScreen(
            onBack = { navController.popBackStack() },
            onVideoClick = { video -> mediaNavigator.openLink(YouTubeLink.Video(video.id, isMusic = false)) },
            onChannelClick = mediaNavigator::openChannel,
        )
    }
}
