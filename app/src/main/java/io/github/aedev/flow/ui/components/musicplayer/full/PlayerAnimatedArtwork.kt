package io.github.aedev.flow.ui.components.musicplayer.full

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.github.aedev.flow.R
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.ui.screens.music.AnimatedArtworkViewModel

/**
 * The animated artwork for the open player's [track], or null while it may not run: it needs the
 * setting on, the player open and in front ([visible]), and the app on screen. Lookups follow the
 * same rule, so a collapsed player never asks for one. [tallSpace] asks for the loop cut for a tall
 * screen, and [shown] fades it out without releasing it.
 */
@Composable
internal fun rememberAnimatedArtwork(
    track: MusicTrack?,
    visible: Boolean,
    tallSpace: Boolean,
    shown: Boolean,
    viewModel: AnimatedArtworkViewModel = hiltViewModel(),
): (@Composable () -> Unit)? {
    val enabled by viewModel.isEnabled.collectAsState()
    val loop by viewModel.loop.collectAsState()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = enabled && visible && lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    LaunchedEffect(track, active) { if (active && track != null) viewModel.load(track) }
    val url = loop?.urlFor(tallSpace)?.takeIf { active } ?: return null
    return {
        PlayerAnimatedArtwork(
            url = url,
            shown = shown,
            dataSourceFactory = viewModel.dataSourceFactory,
            onFailed = viewModel::onLoopFailed,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * An album's motion artwork looping over its cover. It has a muted player of its own that never
 * takes audio focus, fades in on its first frame, and is released when it leaves composition, so
 * the caller decides when it may run. It keeps moving while the song is paused, and [shown] hides it
 * without tearing it down, as during an artwork swipe.
 */
@OptIn(UnstableApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlayerAnimatedArtwork(
    url: String,
    shown: Boolean,
    dataSourceFactory: DataSource.Factory,
    onFailed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val reportFailure by rememberUpdatedState(onFailed)
    var hasFrame by remember(url) { mutableStateOf(false) }
    val player =
        remember(dataSourceFactory) {
            ExoPlayer
                .Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .build()
                .apply {
                    repeatMode = Player.REPEAT_MODE_ONE
                    volume = 0f
                    playWhenReady = true
                    trackSelectionParameters =
                        trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                            .build()
                }
        }
    DisposableEffect(player) { onDispose { player.release() } }

    DisposableEffect(player, url) {
        val listener =
            object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    hasFrame = true
                }

                override fun onPlayerError(error: PlaybackException) = reportFailure()
            }
        player.addListener(listener)
        player.setMediaItem(
            MediaItem
                .Builder()
                .setUri(url)
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build(),
        )
        player.prepare()
        onDispose { player.removeListener(listener) }
    }
    val alpha by animateFloatAsState(
        targetValue = if (hasFrame && shown) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "animatedArtworkAlpha",
    )

    BoxWithConstraints(modifier = modifier) {
        // The smallest variant that covers this box: a larger one would only cost data.
        val widthPx = constraints.maxWidth
        val heightPx = constraints.maxHeight
        LaunchedEffect(player, widthPx, heightPx) {
            if (widthPx == Constraints.Infinity || heightPx == Constraints.Infinity) return@LaunchedEffect
            player.trackSelectionParameters =
                player.trackSelectionParameters
                    .buildUpon()
                    .setViewportSize(widthPx, heightPx, false)
                    .build()
        }
        AndroidView(
            factory = { viewContext ->
                // A TextureView, unlike a SurfaceView, fades and clips with the artwork around it.
                (LayoutInflater.from(viewContext).inflate(R.layout.video_player_view, null) as PlayerView).apply {
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    artworkDisplayMode = PlayerView.ARTWORK_DISPLAY_MODE_OFF
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    subtitleView?.visibility = View.GONE
                    this.player = player
                }
            },
            onRelease = { it.player = null },
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { this.alpha = alpha },
        )
    }
}
