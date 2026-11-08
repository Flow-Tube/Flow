package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.player.SleepTimerManager
import io.github.aedev.flow.ui.components.shared.FlowBottomSheet
import io.github.aedev.flow.ui.components.shared.FlowSheetHeader
import io.github.aedev.flow.ui.components.shared.rememberFlowBottomSheetState
import io.github.aedev.flow.ui.utils.flowWindowDpSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The sleep timer sheet. With no [expandedHeight] it wraps its content, as the music player opens
 * it; the video player passes the height its other sheets share so the video stays uncovered.
 */
@Composable
fun MediaSleepTimerSheet(
    onDismiss: () -> Unit,
    expandedHeight: Dp? = null,
    collapsedHeight: Dp = 0.dp,
    enableVerticalDismiss: Boolean = true,
    asBottomSheet: Boolean = true,
    onSheetProgressChange: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val isActive = SleepTimerManager.isActive
    val pauseAtEndOfMedia = SleepTimerManager.pauseAtEndOfMedia
    val activeCloseAppOnExpiry = SleepTimerManager.closeAppOnExpiry
    val triggerTimeMs = SleepTimerManager.triggerTimeMs
    val context = LocalContext.current
    val playerPreferences = remember(context) { PlayerPreferences(context) }
    val preferredCloseAppOnExpiry by playerPreferences.sleepTimerCloseAppOnExpiry.collectAsState(
        initial = SleepTimerManager.preferredCloseAppOnExpiry,
    )
    val coroutineScope = rememberCoroutineScope()

    var remainingMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(isActive, triggerTimeMs) {
        if (isActive && triggerTimeMs > 0L) {
            while (true) {
                remainingMs = (triggerTimeMs - System.currentTimeMillis()).coerceAtLeast(0L)
                if (remainingMs == 0L) break
                delay(500L)
            }
        } else {
            remainingMs = 0L
        }
    }

    var sliderValue by remember { mutableFloatStateOf(30f) }
    var customInput by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf(false) }
    var closeApp by remember {
        mutableStateOf(
            if (isActive) activeCloseAppOnExpiry else SleepTimerManager.preferredCloseAppOnExpiry,
        )
    }

    LaunchedEffect(preferredCloseAppOnExpiry, isActive) {
        SleepTimerManager.updatePreferredCloseAppOnExpiry(preferredCloseAppOnExpiry)
        if (!isActive) {
            closeApp = preferredCloseAppOnExpiry
        }
    }

    LaunchedEffect(isActive, activeCloseAppOnExpiry) {
        if (isActive) {
            closeApp = activeCloseAppOnExpiry
        }
    }

    val body: @Composable (Modifier) -> Unit = { bodyModifier ->
        SleepTimerBody(
            isActive = isActive,
            pauseAtEndOfMedia = pauseAtEndOfMedia,
            remainingMs = remainingMs,
            sliderValue = sliderValue,
            onSliderChange = { value ->
                sliderValue = value
                if (customInput.isEmpty() || customInput.toIntOrNull() != null) {
                    customInput = value.roundToInt().toString()
                    inputError = false
                }
            },
            customInput = customInput,
            onCustomInputChange = { text ->
                customInput = text
                inputError = false
                val parsed = text.toIntOrNull()
                if (parsed != null && parsed in 1..1440) {
                    sliderValue = parsed.toFloat().coerceIn(5f, 120f)
                }
            },
            inputError = inputError,
            onEndOfMedia = {
                SleepTimerManager.startEndOfMedia(closeApp)
                onDismiss()
            },
            closeAppOnExpiry = closeApp,
            onCloseAppToggle = { enabled ->
                closeApp = enabled
                SleepTimerManager.updatePreferredCloseAppOnExpiry(enabled)
                coroutineScope.launch {
                    playerPreferences.setSleepTimerCloseAppOnExpiry(enabled)
                }
            },
            modifier = bodyModifier,
        )
    }
    val actions: @Composable (Modifier) -> Unit = { actionsModifier ->
        SleepTimerActions(
            isActive = isActive,
            pauseAtEndOfMedia = pauseAtEndOfMedia,
            onCancel = onDismiss,
            onStart = {
                val minutes = customInput.toIntOrNull()
                when {
                    customInput.isNotEmpty() && (minutes == null || minutes < 1 || minutes > 1440) -> {
                        inputError = true
                    }

                    customInput.isNotEmpty() && minutes != null -> {
                        SleepTimerManager.start(minutes, closeApp)
                        onDismiss()
                    }

                    else -> {
                        SleepTimerManager.start(sliderValue.roundToInt(), closeApp)
                        onDismiss()
                    }
                }
            },
            onReset = {
                SleepTimerManager.start(sliderValue.roundToInt(), closeApp)
            },
            onCancelTimer = {
                SleepTimerManager.cancel()
                onDismiss()
            },
            modifier = actionsModifier,
        )
    }

    if (asBottomSheet) {
        FlowSleepTimerBottomSheet(
            onDismiss = onDismiss,
            expandedHeight = expandedHeight,
            collapsedHeight = collapsedHeight,
            enableVerticalDismiss = enableVerticalDismiss,
            onSheetProgressChange = onSheetProgressChange,
            modifier = modifier,
            body = body,
            actions = actions,
        )
    } else {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                SleepTimerPanelHeader(
                    onDismiss = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                )
                SleepTimerColumn(fillHeight = true, body = body, actions = actions)
            }
        }
    }
}

/**
 * The body scrolls and the actions stay pinned under it. A sheet with a fixed height pushes the
 * actions to its bottom edge; a sheet that wraps its content keeps them right under the body.
 */
@Composable
private fun ColumnScope.SleepTimerColumn(
    fillHeight: Boolean,
    body: @Composable (Modifier) -> Unit,
    actions: @Composable (Modifier) -> Unit,
) {
    body(
        Modifier
            .fillMaxWidth()
            .weight(1f, fill = fillHeight)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f))
    actions(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

@Composable
private fun FlowSleepTimerBottomSheet(
    onDismiss: () -> Unit,
    expandedHeight: Dp?,
    collapsedHeight: Dp,
    enableVerticalDismiss: Boolean,
    onSheetProgressChange: (Float) -> Unit,
    modifier: Modifier,
    body: @Composable (Modifier) -> Unit,
    actions: @Composable (Modifier) -> Unit,
) {
    val sheetState = rememberFlowBottomSheetState()
    FlowBottomSheet(
        onDismiss = onDismiss,
        modifier = modifier,
        state = sheetState,
        expandedHeight = expandedHeight,
        maxHeight = if (expandedHeight == null) wrappedSheetMaxHeight() else null,
        collapsedHeight = collapsedHeight,
        dismissible = enableVerticalDismiss,
        dismissOnOutsideTap = false,
        shape = RectangleShape,
        containerColor = MaterialTheme.colorScheme.surface,
        onProgressChange = onSheetProgressChange,
        header = { dragModifier ->
            FlowSheetHeader(
                inSidePane = !enableVerticalDismiss,
                title = stringResource(R.string.sleep_timer),
                onClose = { sheetState.dismiss() },
                modifier = dragModifier,
                leadingIcon = Icons.Outlined.Bedtime,
                contentPadding = PaddingValues(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
                closeButtonSize = null,
                dividerAlpha = 0.18f,
            )
        },
    ) {
        SleepTimerColumn(fillHeight = expandedHeight != null, body = body, actions = actions)
    }
}

@Composable
private fun wrappedSheetMaxHeight(): Dp = flowWindowDpSize().height * WRAPPED_SHEET_MAX_HEIGHT_FRACTION

/** The same header without the drag handle, for the fullscreen side panel that hosts this sheet. */
@Composable
private fun SleepTimerPanelHeader(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Bedtime,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = stringResource(R.string.sleep_timer),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f))
    }
}

private const val WRAPPED_SHEET_MAX_HEIGHT_FRACTION = 0.9f
