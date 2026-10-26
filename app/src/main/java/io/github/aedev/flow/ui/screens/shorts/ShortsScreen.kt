package io.github.aedev.flow.ui.screens.shorts

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.model.toVideo
import io.github.aedev.flow.data.shorts.queue.ShortsQueueSource
import io.github.aedev.flow.player.GlobalPlayerState
import io.github.aedev.flow.player.shorts.ShortsPlayerPool
import io.github.aedev.flow.ui.components.shared.CommentSortFilter
import io.github.aedev.flow.ui.components.shared.FlowCommentsBottomSheet
import io.github.aedev.flow.ui.components.shared.FlowDescriptionBottomSheet
import io.github.aedev.flow.ui.components.shared.FlowErrorState
import io.github.aedev.flow.ui.components.shared.FlowLoadingIndicator
import io.github.aedev.flow.ui.components.shared.MediaSheetSlot
import io.github.aedev.flow.ui.components.shared.applyVideoCommentFilters
import io.github.aedev.flow.ui.components.shared.defaultSheetExpandedHeight
import io.github.aedev.flow.ui.components.shared.rememberVideoShareAction
import io.github.aedev.flow.ui.components.shared.videoCommentSortFor
import io.github.aedev.flow.ui.components.shorts.SHORTS_SHEET_HEIGHT_FRACTION
import io.github.aedev.flow.ui.components.shorts.ShortsDownloadSheet
import io.github.aedev.flow.ui.components.shorts.ShortsReelActions
import io.github.aedev.flow.ui.components.shorts.ShortsReelPage
import io.github.aedev.flow.ui.components.shorts.ShortsSettingsSheet
import io.github.aedev.flow.ui.components.shorts.ShortsSettingsSheetState
import io.github.aedev.flow.ui.components.shorts.ShortsTopBar
import io.github.aedev.flow.ui.components.shorts.rememberShortsReelSettings
import io.github.aedev.flow.ui.components.shorts.rememberShortsSheetInsetState
import io.github.aedev.flow.ui.theme.PlayerScrim
import io.github.aedev.flow.ui.utils.rememberIsOnWifi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private val SnackbarBottomPadding = 80.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShortsScreen(
    source: ShortsQueueSource,
    onBack: () -> Unit,
    onChannelClick: (String) -> Unit,
    bottomNavOverlayPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
    viewModel: ShortsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val playerPreferences = remember(context) { PlayerPreferences(context) }
    val reelSettings = rememberShortsReelSettings(playerPreferences)
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val shareVideo = rememberVideoShareAction()

    val isInPip by GlobalPlayerState.isInPipMode.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { message ->
            snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Short)
            viewModel.clearSnackbar()
        }
    }

    val isWifi = rememberIsOnWifi()
    // Null until DataStore has actually emitted. Resolving against a placeholder height would key
    // the playback-stream cache differently from the ViewModel's prefetch (which reads the real
    // preference), guaranteeing a miss — and the correction would then re-resolve and reload all
    // three pooled players on every entry to the screen.
    val shortsQualityPair by remember(playerPreferences) {
        playerPreferences.shortsQualityWifi.combine(playerPreferences.shortsQualityCellular, ::Pair)
    }.collectAsState(initial = null)
    val shortsTargetHeight by remember(isWifi, shortsQualityPair) {
        derivedStateOf { shortsQualityPair?.let { (wifi, cellular) -> shortsTargetHeight(isWifi, wifi, cellular) } }
    }

    var showCommentsSheet by remember { mutableStateOf(false) }
    var showDescriptionSheet by remember { mutableStateOf(false) }
    val settingsSheet = remember { ShortsSettingsSheetState() }
    var commentSortFilter by remember { mutableStateOf(CommentSortFilter.TOP) }
    var commentsTimedOnly by remember { mutableStateOf(false) }
    val comments by viewModel.commentsState.collectAsState()
    val isLoadingComments by viewModel.isLoadingComments.collectAsState()
    val commentSortOptions by viewModel.commentSortOptions.collectAsState()
    val commentTotalText by viewModel.commentTotalText.collectAsState()
    val visibleComments =
        remember(comments, commentSortFilter, commentsTimedOnly) {
            applyVideoCommentFilters(comments, commentSortFilter, commentsTimedOnly)
        }

    LaunchedEffect(source) { viewModel.load(source) }

    // Release the pool on the way out — unless a later Shorts screen has claimed it in the meantime.
    // An external /shorts/ link arriving while the Shorts tab is open pushes a second destination,
    // and the outgoing screen's dispose runs after the incoming one has already prepared its players.
    DisposableEffect(Unit) {
        val playerPool = ShortsPlayerPool.getInstance()
        val hostToken = playerPool.acquireHost()
        viewModel.onScreenVisible()
        onDispose {
            viewModel.onScreenHidden()
            playerPool.releaseIfHost(hostToken)
        }
    }

    val sheetInsets = rememberShortsSheetInsetState()
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .background(PlayerScrim),
    ) {
        val canShrinkReel = maxHeight > maxWidth
        val sheetExpandedHeight = (maxHeight * SHORTS_SHEET_HEIGHT_FRACTION).takeIf { canShrinkReel }
        val sheetExpandedHeightPx = with(density) { (sheetExpandedHeight ?: 0.dp).toPx() }
        SideEffect {
            sheetInsets.containerHeightPx = constraints.maxHeight.toFloat()
            sheetInsets.shrinkEnabled = canShrinkReel
        }
        val screenSheetOpen = showCommentsSheet || showDescriptionSheet || settingsSheet.isOpen || settingsSheet.downloadVideo != null

        when {
            uiState.isLoading && uiState.shorts.isEmpty() -> {
                FlowLoadingIndicator()
            }

            uiState.error != null && uiState.shorts.isEmpty() -> {
                FlowErrorState(
                    error = uiState.error ?: stringResource(R.string.error_short_load),
                    onRetry = { viewModel.retry(source) },
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            uiState.shorts.isNotEmpty() -> {
                val pagerState = rememberPagerState(initialPage = uiState.currentIndex, pageCount = { uiState.shorts.size })
                ShortsPipActionEffect(pagerState = pagerState, isInPip = isInPip)

                LaunchedEffect(pagerState.currentPage) { viewModel.updateCurrentIndex(pagerState.currentPage) }
                LaunchedEffect(pagerState.settledPage) {
                    if (settingsSheet.isOpen && settingsSheet.targetIndex != pagerState.settledPage) settingsSheet.close()
                }

                ShortsPagerPlaybackEffects(
                    pagerState = pagerState,
                    shorts = uiState.shorts,
                    targetHeight = shortsTargetHeight,
                    playerPreferences = playerPreferences,
                    viewModel = viewModel,
                )

                VerticalPager(
                    state = pagerState,
                    modifier =
                        Modifier.fillMaxSize().shortsKeyboardShortcuts(
                            enabled = !screenSheetOpen && !isInPip,
                            pagerState = pagerState,
                            shortIdAt = { uiState.shorts.getOrNull(it)?.id },
                            playerPreferences = playerPreferences,
                        ),
                    beyondViewportPageCount = 1,
                    key = { uiState.shorts[it].id },
                ) { page ->
                    val short = uiState.shorts[page]
                    ShortsReelPage(
                        short = short,
                        isActive = page == pagerState.currentPage,
                        pageIndex = page,
                        viewModel = viewModel,
                        settings = reelSettings,
                        sheetInsets = sheetInsets,
                        screenSheetOpen = screenSheetOpen,
                        bottomNavOverlayPadding = bottomNavOverlayPadding,
                        actions =
                            ShortsReelActions(
                                onChannelClick = { onChannelClick(short.channelId) },
                                onCommentsClick = {
                                    viewModel.loadComments(short.id)
                                    showCommentsSheet = true
                                },
                                onDescriptionClick = {
                                    viewModel.loadShortDescription(short.id)
                                    showDescriptionSheet = true
                                },
                                onShareClick = { shareVideo(short.id, short.title, true) },
                                onMoreClick = { settingsSheet.open(page, short.id) },
                                onVideoEnded = {
                                    scope.launch {
                                        if (page < pagerState.pageCount - 1) pagerState.animateScrollToPage(page + 1)
                                    }
                                },
                            ),
                    )
                }

                if (uiState.isLoadingMore && !isInPip) {
                    LinearProgressIndicator(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter),
                    )
                }
            }
        }

        if (showCommentsSheet) {
            DisposableEffect(Unit) { onDispose { sheetInsets.release() } }
            FlowCommentsBottomSheet(
                comments = visibleComments,
                isLoading = isLoadingComments,
                selectedFilter = commentSortFilter,
                totalText = commentTotalText,
                timedOnly = commentsTimedOnly,
                onTimedChange = { commentsTimedOnly = it },
                onFilterChanged = { filter ->
                    commentSortFilter = filter
                    videoCommentSortFor(commentSortOptions, filter)?.let { sort ->
                        uiState.shorts
                            .getOrNull(uiState.currentIndex)
                            ?.id
                            ?.let { videoId -> viewModel.selectCommentSort(videoId, sort) }
                    }
                },
                onLoadReplies = { viewModel.loadCommentReplies(it) },
                onAuthorClick = { authorChannelRef ->
                    showCommentsSheet = false
                    onChannelClick(authorChannelRef)
                },
                expandedHeight = sheetExpandedHeight,
                onSheetProgressChange = { progress -> sheetInsets.follow(sheetExpandedHeightPx * progress) },
                dismissOnOutsideTap = true,
                onDismiss = { showCommentsSheet = false },
            )
        }

        if (showDescriptionSheet && uiState.shorts.isNotEmpty()) {
            DisposableEffect(Unit) { onDispose { sheetInsets.release() } }
            val safeIndex = uiState.currentIndex.coerceIn(0, uiState.shorts.size - 1)
            FlowDescriptionBottomSheet(
                video = uiState.shorts[safeIndex].toVideo(),
                expandedHeight = sheetExpandedHeight,
                onSheetProgressChange = { progress -> sheetInsets.follow(sheetExpandedHeightPx * progress) },
                dismissOnOutsideTap = true,
                onDismiss = { showDescriptionSheet = false },
            )
        }

        val settingsShort = settingsSheet.targetId?.let { id -> uiState.shorts.firstOrNull { it.id == id } }
        if (settingsShort != null) {
            DisposableEffect(Unit) { onDispose { sheetInsets.release() } }
            ShortsSettingsSheet(
                short = settingsShort,
                settings = reelSettings,
                state = settingsSheet,
                playerPool = ShortsPlayerPool.getInstance(),
                viewModel = viewModel,
                playerPreferences = playerPreferences,
                onWantMore = { viewModel.wantMoreLikeThis(settingsShort) },
                onNotInterested = { viewModel.notInterested(settingsShort) },
                onBlockChannel = { viewModel.blockChannel(settingsShort) },
                onDownload = { scope.launch { settingsSheet.prepareDownload(settingsShort, viewModel) } },
                onDismiss = settingsSheet::close,
                expandedHeight = sheetExpandedHeight,
                onSheetProgressChange = { progress -> sheetInsets.follow(sheetExpandedHeightPx * progress) },
                bottomContentPadding = bottomNavOverlayPadding,
            )
        }
        if (settingsSheet.downloadVideo != null) {
            DisposableEffect(Unit) { onDispose { sheetInsets.release() } }
            ShortsDownloadSheet(
                state = settingsSheet,
                slot =
                    MediaSheetSlot(
                        expandedHeight = sheetExpandedHeight ?: defaultSheetExpandedHeight(),
                        bottomPadding = bottomNavOverlayPadding,
                        onProgressChange = { progress -> sheetInsets.follow(sheetExpandedHeightPx * progress) },
                    ),
            )
        }

        ShortsTopBar(
            visible = uiState.shorts.isNotEmpty() && !isInPip,
            showBackButton = source != ShortsQueueSource.Feed,
            onBack = onBack,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        if (isInPip) return@BoxWithConstraints
        SnackbarHost(
            hostState = snackbarHostState,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = SnackbarBottomPadding),
        ) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shape = MaterialTheme.shapes.medium,
            )
        }
    }
}
