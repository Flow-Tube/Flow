package io.github.aedev.flow.ui.components.videoplayer

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import io.github.aedev.flow.ui.components.videoplayer.motion.BODY_CONTENT_MAX_EXPAND_FRACTION
import io.github.aedev.flow.ui.components.videoplayer.motion.BODY_SLIDE_PX
import io.github.aedev.flow.ui.components.videoplayer.motion.DraggablePlayerGestureHandler
import io.github.aedev.flow.ui.components.videoplayer.motion.DraggablePlayerGestureMetrics
import io.github.aedev.flow.ui.components.videoplayer.motion.MiniPlayerPinchGestureHandler
import io.github.aedev.flow.ui.components.videoplayer.motion.PlayerBodyNestedScrollConnection
import io.github.aedev.flow.ui.components.videoplayer.motion.RoundRectClipShape
import io.github.aedev.flow.ui.components.videoplayer.motion.computeDraggablePlayerGeometry
import io.github.aedev.flow.ui.components.videoplayer.motion.draggablePlayerGestures
import io.github.aedev.flow.ui.components.videoplayer.motion.keyboardSafeMiniY
import io.github.aedev.flow.ui.components.videoplayer.motion.lerpClamped
import io.github.aedev.flow.ui.components.videoplayer.motion.miniPlayerPinchGesture
import io.github.aedev.flow.ui.components.videoplayer.motion.morphCornerRadiusPx
import io.github.aedev.flow.ui.components.videoplayer.motion.openBodyAlpha
import io.github.aedev.flow.ui.components.videoplayer.motion.openGroundCornerRadius
import io.github.aedev.flow.ui.components.videoplayer.motion.openGroundRect
import io.github.aedev.flow.ui.components.videoplayer.motion.resnapTargets
import io.github.aedev.flow.ui.components.videoplayer.motion.resolveOpenOriginRect
import io.github.aedev.flow.ui.components.videoplayer.motion.tallVideoMaxHeight
import io.github.aedev.flow.ui.components.videoplayer.motion.update
import io.github.aedev.flow.ui.theme.PlayerGround
import kotlin.math.roundToInt

/**
 * Corner radius of the floating mini player, authored pre-scale: the video box is clipped by the
 * PlayerView outline at `radius / miniVisualScale` so the morph's graphicsLayer scale brings it
 * back to this on screen.
 */
const val MINI_PLAYER_CORNER_RADIUS_DP = 12f

private val MiniPlayerMargin = 8.dp

/**
 * What the mini player is worth on a window too wide to size it by fraction: the width the default
 * size setting gets, which the smaller and larger settings scale around.
 */
private val MiniPlayerLargeWindowWidth = 260.dp
private const val DEFAULT_MINI_PLAYER_SCALE = 0.45f
private val PortraitFullscreenActivation = 28.dp
private val MiniPlayerShadowElevation = 8.dp

/** Page a tall video always leaves on screen under it: the title, the stats and the action row. */
private val TallVideoMinPageHeight = 120.dp

/**
 * The video player as one box that is laid out once at its expanded size and morphed into the
 * floating mini player purely through a graphicsLayer scale and translation. Every animated
 * value is read in the layout or draw phase; composition only sees settled booleans.
 *
 * @param isLargeWindow the window can host one of the player's detail layouts, so the video keeps
 *   the top of the window in landscape instead of going immersive and the mini player is sized
 *   from the window rather than the user's scale.
 * @param isTwoPaneWindow the body puts the detail pane beside the video, so the expanded video
 *   only takes the leading part of the width.
 * @param detailPaneWidth the width the detail pane and its spacer take from the video's row in a
 *   two-pane window; the expanded video gets what is left.
 */
@Composable
fun DraggablePlayerLayout(
    state: PlayerDraggableState,
    videoContent: @Composable (Modifier) -> Unit,
    bodyContent: @Composable (alpha: () -> Float, videoHeightPx: () -> Float) -> Unit,
    miniControls: @Composable (() -> Float) -> Unit,
    progress: () -> Float,
    isFullscreen: Boolean,
    thumbnailUrl: String? = null,
    topPadding: Dp = 56.dp,
    bottomPadding: Dp = 0.dp,
    miniPlayerScale: Float = 0.45f,
    isLargeWindow: Boolean = false,
    startInset: Dp = 0.dp,
    isTwoPaneWindow: Boolean = false,
    detailPaneWidth: Dp = 0.dp,
    tapToExpand: Boolean = true,
    onDismiss: () -> Unit = {},
    onCollapseGesture: (() -> Unit)? = null,
    onFullscreenGesture: (() -> Unit)? = null,
    onEnterPortraitFullscreen: (() -> Unit)? = null,
    onExpandedPlayerBottomChanged: (Dp) -> Unit = {},
    videoAspectRatio: Float = 16f / 9f,
    expandedPlayerHeightFractionOverride: (() -> Float)? = null,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE

    var playerHeightFraction by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(videoAspectRatio) { playerHeightFraction = 1f }

    LaunchedEffect(isFullscreen) {
        if (!isFullscreen) state.settlePortraitFullscreen()
    }

    val statusBarHeight = WindowInsets.statusBars.getTop(density).toFloat()
    val systemLayoutDirection = LocalLayoutDirection.current
    val windowPosition = remember { WindowPosition() }

    // A playing video votes its own 30 or 60 fps and the window only a "normal" category, so the
    // sheet's motion ran at 60 fps on a 120 Hz panel. The peak rate is asked for only while the sheet
    // moves, so a player at rest never holds the display up.
    val view = LocalView.current
    val peakRefreshRate = remember(view) { view.display?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 0f }
    val isSheetMoving by remember(state) {
        derivedStateOf { !state.isSettled || state.portraitFsFraction.let { it > 0f && it != 1f } }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(
            modifier =
                modifier
                    .fillMaxSize()
                    .preferredFrameRate(if (isSheetMoving) peakRefreshRate else 0f)
                    .onPlaced { coordinates ->
                        val position = coordinates.positionInWindow()
                        windowPosition.x = position.x
                        windowPosition.y = position.y
                    },
        ) {
            val screenWidth = constraints.maxWidth.toFloat()
            val screenHeight = constraints.maxHeight.toFloat()
            val showImmersiveFullscreen =
                isImmersivePlayer(
                    isExpanded = state.currentValue == PlayerSheetValue.Expanded,
                    isFullscreen = isFullscreen,
                    isLandscape = isLandscape,
                    isLargeWindow = isLargeWindow,
                )

            val geometry =
                computeDraggablePlayerGeometry(
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    statusBarHeight = statusBarHeight,
                    margin = with(density) { MiniPlayerMargin.toPx() },
                    startInset = with(density) { startInset.toPx() },
                    bottomNavPad = with(density) { bottomPadding.toPx() },
                    topBarPad = with(density) { topPadding.toPx() },
                    isLargeWindow = isLargeWindow,
                    isTwoPaneWindow = isTwoPaneWindow,
                    detailPaneWidth = with(density) { detailPaneWidth.toPx() },
                    miniPlayerScale = miniPlayerScale,
                    maxMiniWidthPx =
                        if (isLargeWindow || isTwoPaneWindow) {
                            with(density) {
                                (MiniPlayerLargeWindowWidth * (miniPlayerScale / DEFAULT_MINI_PLAYER_SCALE)).toPx()
                            }
                        } else {
                            Float.MAX_VALUE
                        },
                    videoAspectRatio = videoAspectRatio,
                    currentSizeScale = state.miniSizeScale.targetValue,
                    corner = state.corner,
                    isShrinkingToCorner = state.isShrinkingToCorner,
                    cachedTargetX = state.cachedTargetX,
                    offsetXFallback = { state.offsetX.value },
                )
            val expandedVideoWidth = geometry.expandedVideoWidth
            val visualMiniScale = geometry.visualMiniScale

            val expandedVideoHeight =
                rememberGlidingVideoHeight(state, expandedVideoWidth, geometry.expandedVideoHeight)
            val tallVideoCeiling =
                tallVideoMaxHeight(
                    screenHeight = screenHeight,
                    statusBarHeight = statusBarHeight,
                    navigationBarHeight = WindowInsets.navigationBars.getBottom(density).toFloat(),
                    minPageHeight = with(density) { TallVideoMinPageHeight.toPx() },
                    baseVideoHeight = geometry.baseVideoHeight,
                )
            val heightFractionOverrideState = rememberUpdatedState(expandedPlayerHeightFractionOverride)
            // Read in the layout phase only: the fraction changes on every nested-scroll delta and
            // every media-sheet drag frame, and a composition read here recomposed this whole tree.
            val videoHeightUnder =
                remember(geometry.baseVideoHeight, expandedVideoHeight) {
                    { ceiling: Float ->
                        val fullHeight = expandedVideoHeight().coerceAtMost(ceiling)
                        if (fullHeight > geometry.baseVideoHeight) {
                            val fraction =
                                heightFractionOverrideState.value?.invoke()?.coerceIn(0f, 1f)
                                    ?: playerHeightFraction
                            lerpClamped(geometry.baseVideoHeight, fullHeight, fraction)
                        } else {
                            fullHeight
                        }
                    }
                }
            val restingVideoHeightProvider = remember(videoHeightUnder, tallVideoCeiling) { { videoHeightUnder(tallVideoCeiling) } }
            // The ceiling lifts as the player collapses, so the mini player keeps the video's own shape.
            val currentExpandedVideoHeightProvider =
                remember(videoHeightUnder, expandedVideoHeight, tallVideoCeiling) {
                    {
                        val lifted = expandedVideoHeight().coerceAtLeast(tallVideoCeiling)
                        videoHeightUnder(lerpClamped(tallVideoCeiling, lifted, state.expandFraction.value))
                    }
                }
            val openRectProvider =
                remember(screenHeight, expandedVideoWidth, currentExpandedVideoHeightProvider) {
                    {
                        resolveOpenOriginRect(
                            origin = state.openOrigin,
                            layoutLeft = windowPosition.x,
                            layoutTop = windowPosition.y,
                            screenHeight = screenHeight,
                            expandedVideoWidth = expandedVideoWidth,
                            expandedVideoHeight = currentExpandedVideoHeightProvider(),
                        )
                    }
                }
            val miniCornerRadiusPx = with(density) { MINI_PLAYER_CORNER_RADIUS_DP.dp.toPx() }
            val imeInsets = WindowInsets.ime
            val miniMarginPx = with(density) { MiniPlayerMargin.toPx() }
            // The keyboard is read in the layout and draw phases only, so it moving does not recompose.
            val miniRestingY =
                remember(geometry.miniHeight, geometry.minY, screenHeight, miniMarginPx, density) {
                    {
                        keyboardSafeMiniY(
                            offsetY = state.offsetY.value,
                            miniHeight = geometry.miniHeight,
                            screenHeight = screenHeight,
                            imeBottom = imeInsets.getBottom(density).toFloat(),
                            margin = miniMarginPx,
                            minY = geometry.minY,
                        )
                    }
                }
            val cornerRadiusProvider =
                remember(openRectProvider, expandedVideoWidth, miniCornerRadiusPx, visualMiniScale) {
                    {
                        morphCornerRadiusPx(
                            fraction = state.expandFraction.value,
                            origin = openRectProvider(),
                            expandedVideoWidth = expandedVideoWidth,
                            miniCornerRadiusPx = miniCornerRadiusPx,
                            visualMiniScale = visualMiniScale,
                        )
                    }
                }

            ReportExpandedPlayerBottom(
                statusBarHeight = statusBarHeight,
                videoHeightProvider = restingVideoHeightProvider,
                onChanged = onExpandedPlayerBottomChanged,
            )

            SideEffect {
                state.morphCornerRadiusPx = cornerRadiusProvider
                state.miniVisualScale = visualMiniScale
                state.cachedTargetX = geometry.normalTargetX
                state.cachedTargetY = geometry.normalTargetY
            }

            val isCollapsedTarget by remember(state) {
                derivedStateOf { state.expandFraction.targetValue > 0.5f }
            }
            LaunchedEffect(isCollapsedTarget) {
                if (isCollapsedTarget) playerHeightFraction = 1f
            }

            MiniPlayerResnapEffect(
                state = state,
                isCollapsedTarget = isCollapsedTarget,
                targets = geometry.resnapTargets(isLargeScreen = isLargeWindow),
            )

            // How far the video travels from the top of the page to the centre of the window, where
            // the immersive layout shows it. The pull maps a finger to this distance and the page
            // slides by the same amount, so the page stays under the finger and glued to the video.
            val portraitFsTravelProvider =
                remember(screenHeight, statusBarHeight, currentExpandedVideoHeightProvider) {
                    {
                        ((screenHeight - currentExpandedVideoHeightProvider()) / 2f - statusBarHeight)
                            .coerceAtLeast(1f)
                    }
                }
            // While immersive in portrait the video already sits at the centre, so an exit starts its
            // slide from there; landscape rotates instead and has no slide.
            LaunchedEffect(showImmersiveFullscreen, isLandscape) {
                if (showImmersiveFullscreen) state.snapPortraitFullscreen(if (isLandscape) 0f else 1f)
            }
            val portraitFsEnabled =
                !isLandscape && !isLargeWindow && !isFullscreen &&
                    onEnterPortraitFullscreen != null
            val portraitFsActivationPx = with(density) { PortraitFullscreenActivation.toPx() }
            val portraitFsEnabledState = rememberUpdatedState(portraitFsEnabled)
            val portraitFsActivationState = rememberUpdatedState(portraitFsActivationPx)
            val onEnterPortraitFsState = rememberUpdatedState(onEnterPortraitFullscreen)
            val pagedVideoHeight = geometry.expandedVideoHeight.coerceAtMost(tallVideoCeiling)
            val nestedScrollConnection =
                remember(pagedVideoHeight, geometry.baseVideoHeight) {
                    PlayerBodyNestedScrollConnection(
                        expandedVideoHeight = pagedVideoHeight,
                        baseVideoHeight = geometry.baseVideoHeight,
                        playerHeightFraction = { playerHeightFraction },
                        onPlayerHeightFractionChange = { playerHeightFraction = it },
                        portraitFsFraction = { state.portraitFsFraction },
                        onPortraitFsFractionChange = { state.snapPortraitFullscreen(it.coerceIn(0f, 1f)) },
                        animatePortraitFs = state::animatePortraitFullscreen,
                        portraitFsTravel = portraitFsTravelProvider,
                        portraitFsEnabled = { portraitFsEnabledState.value },
                        portraitFsActivationPx = { portraitFsActivationState.value },
                        expandFraction = { state.expandFraction.value },
                        onEnterPortraitFullscreen = { onEnterPortraitFsState.value },
                    )
                }

            if (showImmersiveFullscreen) {
                ImmersiveFullscreenBackdrop(thumbnailUrl = thumbnailUrl)
            } else {
                CollapsingPlayerScrim(state = state, statusBarHeight = statusBarHeight, openRect = openRectProvider)
                PortraitFullscreenBackdropFade(state = state, thumbnailUrl = thumbnailUrl)
            }

            // Kept composed behind a portrait fullscreen video, so leaving it has no page to build.
            if (!showImmersiveFullscreen || !isLandscape) {
                DraggablePlayerPage(
                    state = state,
                    hidden = showImmersiveFullscreen,
                    isTwoPaneWindow = isTwoPaneWindow,
                    statusBarHeight = statusBarHeight,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    videoHeightProvider = currentExpandedVideoHeightProvider,
                    portraitFsFraction = { state.portraitFsFraction },
                    portraitFsTravel = portraitFsTravelProvider,
                    openRect = openRectProvider,
                    nestedScrollConnection = nestedScrollConnection,
                    layoutDirection = systemLayoutDirection,
                    bodyContent = bodyContent,
                )
            }

            val gestureMetrics = remember(state) { DraggablePlayerGestureMetrics() }
            SideEffect {
                gestureMetrics.update(
                    geometry = geometry,
                    isLargeWindow = isLargeWindow,
                    isLandscape = isLandscape,
                    isFullscreen = isFullscreen,
                    tapToExpand = tapToExpand,
                    onFullscreenGesture = onFullscreenGesture,
                    onCollapseGesture = onCollapseGesture,
                    onDismiss = onDismiss,
                )
            }
            val gestureHandler =
                remember(state, gestureMetrics) { DraggablePlayerGestureHandler(state, gestureMetrics) }
            val pinchHandler =
                remember(state, gestureMetrics) { MiniPlayerPinchGestureHandler(state, gestureMetrics) }
            MiniPlayerTuckHandle(
                state = state,
                miniY = miniRestingY,
                miniHeight = geometry.miniHeight,
                onUntuck = gestureHandler::untuck,
            )

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Box(
                    modifier =
                        if (showImmersiveFullscreen) {
                            Modifier.fillMaxSize()
                        } else {
                            Modifier
                                .layout { measurable, constraints ->
                                    val videoHeight = currentExpandedVideoHeightProvider()
                                    state.portraitFsTravel = portraitFsTravelProvider()
                                    val targetW =
                                        expandedVideoWidth
                                            .toInt()
                                            .coerceIn(1, constraints.maxWidth.coerceAtLeast(1))
                                    val targetH =
                                        videoHeight
                                            .toInt()
                                            .coerceIn(1, constraints.maxHeight.coerceAtLeast(1))
                                    val placeable =
                                        measurable.measure(
                                            constraints.copy(
                                                minWidth = targetW,
                                                maxWidth = targetW,
                                                minHeight = targetH,
                                                maxHeight = targetH,
                                            ),
                                        )
                                    layout(targetW, targetH) { placeable.place(0, 0) }
                                }.graphicsLayer {
                                    val fraction = state.expandFraction.value
                                    val origin = openRectProvider()
                                    val endWidth =
                                        origin?.width ?: geometry
                                            .miniBoxWidth(geometry.baseMiniWidth * state.miniSizeScale.value)
                                            .coerceAtMost(geometry.maxWideWidth)
                                    val visualScale =
                                        lerpClamped(
                                            1f,
                                            endWidth / expandedVideoWidth.coerceAtLeast(1f),
                                            fraction,
                                        )
                                    val drag = if (fraction > 0.6f) 1f else state.expandDragScale.value
                                    transformOrigin = TransformOrigin(0f, 0f)
                                    scaleX = visualScale * drag
                                    scaleY = visualScale * drag
                                    val windowW = expandedVideoWidth * visualScale
                                    val windowH = size.height * visualScale
                                    val expandedTopY =
                                        statusBarHeight + state.portraitFsFraction.coerceAtLeast(0f) * portraitFsTravelProvider()
                                    translationX =
                                        lerpClamped(0f, origin?.left ?: state.offsetX.value, fraction) +
                                        windowW * (1f - drag) / 2f
                                    translationY =
                                        lerpClamped(
                                            expandedTopY,
                                            origin?.top ?: miniRestingY(),
                                            fraction,
                                        ) + windowH * (1f - drag) / 2f
                                    shadowElevation =
                                        if (origin == null && fraction > 0.95f) {
                                            MiniPlayerShadowElevation.toPx() / visualMiniScale
                                        } else {
                                            0f
                                        }
                                    shape = RoundedCornerShape(cornerRadiusProvider())
                                    clip = false
                                }.drawBehind {
                                    val r = cornerRadiusProvider()
                                    drawRoundRect(
                                        color = PlayerGround,
                                        cornerRadius = CornerRadius(r, r),
                                    )
                                }
                                // Two pointer nodes with constant keys: the chain must not change
                                // shape while a finger is down or the drag coroutine is reset.
                                .miniPlayerPinchGesture(pinchHandler)
                                .draggablePlayerGestures(gestureHandler)
                        },
                ) {
                    videoContent(Modifier.fillMaxSize())

                    if (!showImmersiveFullscreen) {
                        MiniPlayerControlsLayer(
                            state = state,
                            miniWidth = geometry.miniWidth,
                            miniHeight = geometry.miniHeight,
                            expandedVideoWidth = expandedVideoWidth,
                            progress = progress,
                            miniControls = miniControls,
                        )
                    }
                }
            }
        }
    }
}

/** Where the layout sits in the window; written on placement and read in the draw phase only. */
private class WindowPosition {
    var x = 0f
    var y = 0f
}
