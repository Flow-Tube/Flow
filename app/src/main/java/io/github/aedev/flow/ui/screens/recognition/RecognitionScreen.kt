package io.github.aedev.flow.ui.screens.recognition

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.aedev.flow.R
import io.github.aedev.flow.data.recognition.RecognitionResult
import io.github.aedev.flow.data.recognition.RecognitionStatus
import io.github.aedev.flow.ui.components.layout.LocalFlowBottomInsets
import io.github.aedev.flow.ui.components.layout.topbar.FlowTopBar
import io.github.aedev.flow.ui.components.music.common.rememberMusicArtworkColors
import io.github.aedev.flow.ui.components.shared.FlowSpinner
import io.github.aedev.flow.ui.components.shared.FlowStateIcon
import io.github.aedev.flow.ui.components.shared.flowActionShape
import io.github.aedev.flow.ui.components.shared.flowHeroArtworkSize

private val MicButtonSize = 200.dp
private val MicIconSize = 72.dp
private const val SUBTITLE_ALPHA = 0.8f

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RecognitionScreen(
    onBackClick: () -> Unit,
    onHistoryClick: () -> Unit,
    onPlay: (RecognitionResult) -> Unit,
    onSearch: (RecognitionResult) -> Unit,
    autoStart: Boolean = false,
    viewModel: RecognitionViewModel = hiltViewModel(),
) {
    val status by viewModel.status.collectAsStateWithLifecycle()

    var hasPermission by remember { mutableStateOf(viewModel.hasRecordPermission()) }
    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            hasPermission = granted
            if (granted) viewModel.startRecognition()
        }

    fun start() {
        if (hasPermission) {
            viewModel.startRecognition()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.clearResultOnEnter()
        if (autoStart && status is RecognitionStatus.Ready) start()
    }

    val motion = MaterialTheme.motionScheme

    Scaffold(
        topBar = {
            FlowTopBar(
                title = stringResource(R.string.recognize_music),
                onBack = onBackClick,
                actions = {
                    IconButton(onClick = onHistoryClick) {
                        Icon(Icons.Rounded.History, stringResource(R.string.recognition_history))
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp),
    ) { padding ->
        AnimatedContent(
            targetState = status,
            transitionSpec = {
                (fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec()))
                    .togetherWith(fadeOut(motion.fastEffectsSpec()) + scaleOut(motion.fastSpatialSpec()))
            },
            label = "recognition_content",
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) { state ->
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp)
                        .padding(bottom = LocalFlowBottomInsets.current.contentBottom),
                contentAlignment = Alignment.Center,
            ) {
                when (state) {
                    is RecognitionStatus.Ready -> {
                        RecognitionIdle(onStart = ::start)
                    }

                    is RecognitionStatus.Listening -> {
                        RecognitionInProgress(
                            label = stringResource(R.string.listening),
                            onCancel = viewModel::cancel,
                        )
                    }

                    is RecognitionStatus.Processing -> {
                        RecognitionInProgress(
                            label = stringResource(R.string.processing),
                            onCancel = viewModel::cancel,
                        )
                    }

                    is RecognitionStatus.Success -> {
                        RecognitionResultCard(
                            result = state.result,
                            onPlay = onPlay,
                            onSearch = onSearch,
                            onTryAgain = ::start,
                            onClose = viewModel::cancel,
                            onSave = viewModel::saveToHistory,
                        )
                    }

                    is RecognitionStatus.NoMatch -> {
                        RecognitionMessage(
                            icon = Icons.Rounded.MusicOff,
                            title = stringResource(R.string.no_match_found),
                            message = state.message,
                            onTryAgain = ::start,
                        )
                    }

                    is RecognitionStatus.Error -> {
                        RecognitionMessage(
                            icon = Icons.Rounded.ErrorOutline,
                            title = stringResource(R.string.recognition_error),
                            message = state.message,
                            onTryAgain = ::start,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The microphone button. At rest it wears the twelve-sided cookie; while recognising, the
 * Material loading indicator's morphing shape becomes the button itself, so the animation only
 * ever runs while a recognition is in flight and leaves the tree with it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RecognitionMicButton(
    recognizing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .size(MicButtonSize)
                .clip(CircleShape)
                .clickable(onClick = onClick, role = Role.Button),
        contentAlignment = Alignment.Center,
    ) {
        if (recognizing) {
            FlowSpinner(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(MicButtonSize),
            )
        } else {
            Box(
                modifier =
                    Modifier
                        .size(MicButtonSize)
                        .clip(flowActionShape())
                        .background(MaterialTheme.colorScheme.primary),
            )
        }
        Icon(
            imageVector = Icons.Rounded.Mic,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(MicIconSize),
        )
    }
}

@Composable
private fun RecognitionIdle(onStart: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        RecognitionMicButton(recognizing = false, onClick = onStart)
        Text(
            text = stringResource(R.string.tap_to_recognize),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RecognitionInProgress(
    label: String,
    onCancel: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        RecognitionMicButton(recognizing = true, onClick = onCancel)
        Text(
            text = label,
            style = MaterialTheme.typography.titleMediumEmphasized,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
        OutlinedButton(
            onClick = onCancel,
            shapes = ButtonDefaults.shapes(),
        ) {
            Text(stringResource(R.string.cancel))
        }
    }
}

/**
 * The recognised track in the colours of its own cover, with play and search as the two
 * primary actions and retry and close underneath.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RecognitionResultCard(
    result: RecognitionResult,
    onPlay: (RecognitionResult) -> Unit,
    onSearch: (RecognitionResult) -> Unit,
    onTryAgain: () -> Unit,
    onClose: () -> Unit,
    onSave: (RecognitionResult) -> Unit,
) {
    LaunchedEffect(result) { onSave(result) }

    val coverUrl = result.coverArtHqUrl ?: result.coverArtUrl
    val colors = rememberMusicArtworkColors(coverUrl)
    val artworkSize = flowHeroArtworkSize()
    val buttonHeight = ButtonDefaults.MediumContainerHeight

    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors =
            CardDefaults.cardColors(
                containerColor = colors.container,
                contentColor = colors.onContainer,
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = colors.tonalContainer,
                modifier = Modifier.size(artworkSize),
            ) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = result.title,
                style = MaterialTheme.typography.headlineSmallEmphasized,
                color = colors.onContainer,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = result.artist,
                style = MaterialTheme.typography.titleMedium,
                color = colors.onContainer.copy(alpha = SUBTITLE_ALPHA),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            result.album?.takeIf { it.isNotBlank() }?.let { album ->
                Text(
                    text = album,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onContainer.copy(alpha = SUBTITLE_ALPHA),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (!result.youtubeVideoId.isNullOrBlank()) {
                Button(
                    onClick = { onPlay(result) },
                    shapes = ButtonDefaults.shapesFor(buttonHeight),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = colors.accent,
                            contentColor = colors.onAccent,
                        ),
                    contentPadding = ButtonDefaults.contentPaddingFor(buttonHeight, hasStartIcon = true),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = buttonHeight),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.iconSizeFor(buttonHeight)),
                    )
                    Spacer(modifier = Modifier.width(ButtonDefaults.iconSpacingFor(buttonHeight)))
                    Text(
                        text = stringResource(R.string.play),
                        style = ButtonDefaults.textStyleFor(buttonHeight),
                    )
                }
            }
            Button(
                onClick = { onSearch(result) },
                shapes = ButtonDefaults.shapesFor(buttonHeight),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = colors.tonalContainer,
                        contentColor = colors.onContainer,
                    ),
                contentPadding = ButtonDefaults.contentPaddingFor(buttonHeight, hasStartIcon = true),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = buttonHeight),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.iconSizeFor(buttonHeight)),
                )
                Spacer(modifier = Modifier.width(ButtonDefaults.iconSpacingFor(buttonHeight)))
                Text(
                    text = stringResource(R.string.search_in_flow),
                    style = ButtonDefaults.textStyleFor(buttonHeight),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onTryAgain,
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onContainer),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.try_again))
                }
                OutlinedButton(
                    onClick = onClose,
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onContainer),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.close))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RecognitionMessage(
    icon: ImageVector,
    title: String,
    message: String,
    onTryAgain: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        FlowStateIcon(icon = icon, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = title,
            style = MaterialTheme.typography.titleLargeEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Button(
            onClick = onTryAgain,
            shapes = ButtonDefaults.shapes(),
            contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MinHeight, hasStartIcon = true),
        ) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.try_again))
        }
    }
}
