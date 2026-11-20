package io.github.aedev.flow.data.shorts

import androidx.collection.LruCache
import io.github.aedev.flow.data.comments.CommentsPageResult
import io.github.aedev.flow.data.comments.CommentsSource
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.pages.ReelCommentsPanel
import io.github.aedev.flow.innertube.pages.VideoCommentsPage
import io.github.aedev.flow.innertube.pages.VideoCommentsSection

/**
 * A reel's comments, opened from the comment panel its cached `reel_item_watch` already carries
 * and paged through `/browse`, so the sheet costs no watch page. A reel whose response has no
 * readable panel falls back to [fallback].
 *
 * The first page of each order is kept for the reels recently in the pager, so reopening the sheet
 * on a reel costs nothing.
 */
internal class ShortsCommentsSource(
    private val panelFor: suspend (String) -> ReelCommentsPanel?,
    private val page: suspend (continuation: String, videoId: String) -> VideoCommentsPage?,
    private val replyPage: suspend (continuation: String, videoId: String) -> VideoCommentsPage?,
    private val fallback: CommentsSource,
) : CommentsSource {
    constructor(metadata: ShortsMetadataRepository, fallback: CommentsSource) : this(
        panelFor = { videoId -> metadata.commentsPanelFor(videoId) },
        page = { continuation, videoId -> YouTube.reelComments(continuation, videoId).getOrNull() },
        replyPage = { continuation, videoId -> YouTube.reelCommentReplies(continuation, videoId).getOrNull() },
        fallback = fallback,
    )

    private val firstPages = LruCache<String, CommentsPageResult>(FIRST_PAGES_KEPT)
    private val routes = LruCache<String, Boolean>(ROUTES_KEPT)

    override suspend fun firstPage(
        videoId: String,
        sortToken: String?,
    ): CommentsPageResult {
        val panel = panelFor(videoId)
        routes.put(videoId, panel != null)
        if (panel == null) return fallback.firstPage(videoId, sortToken)
        val token =
            sortToken ?: when (val section = panel.section) {
                is VideoCommentsSection.Open -> section.token
                VideoCommentsSection.TurnedOff -> return CommentsPageResult.DISABLED
            }
        firstPages[token]?.let { return it }
        val first = page(token, videoId) ?: return CommentsPageResult.EMPTY
        val header = panel.header
        return CommentsPageResult(
            comments = first.comments,
            continuation = first.continuation,
            sortOptions = first.sortOptions.ifEmpty { header?.sortOptions.orEmpty() },
            totalText = first.totalText ?: header?.totalText,
            totalCount = first.totalCount ?: header?.totalCount,
        ).also { if (it.comments.isNotEmpty()) firstPages.put(token, it) }
    }

    override suspend fun nextPage(
        videoId: String,
        continuation: String,
    ): CommentsPageResult =
        if (readsPanel(videoId)) {
            page(continuation, videoId)?.let { CommentsPageResult(comments = it.comments, continuation = it.continuation) }
                ?: CommentsPageResult.EMPTY
        } else {
            fallback.nextPage(videoId, continuation)
        }

    override suspend fun replies(
        videoId: String,
        continuation: String,
    ): CommentsPageResult =
        if (readsPanel(videoId)) {
            replyPage(continuation, videoId)?.let { CommentsPageResult(comments = it.comments, continuation = it.continuation) }
                ?: CommentsPageResult.EMPTY
        } else {
            fallback.replies(videoId, continuation)
        }

    // The iOS Timed filter only reads a watch-page section token; a reel's browse tokens have none,
    // so Timed walks the reel's pages instead.
    override suspend fun timed(
        videoId: String,
        sectionToken: String,
    ): CommentsPageResult = if (readsPanel(videoId)) CommentsPageResult.EMPTY else fallback.timed(videoId, sectionToken)

    private suspend fun readsPanel(videoId: String): Boolean =
        routes[videoId] ?: (panelFor(videoId) != null).also { routes.put(videoId, it) }

    private companion object {
        const val FIRST_PAGES_KEPT = 24
        const val ROUTES_KEPT = 64
    }
}
