/*
 * Copyright (C) 2025-2026 Flow | A-EDev
 *
 * This file is part of Flow (https://github.com/Flow-Tube/Flow).
 */

package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R

private val StateIconContainerSize = 96.dp
private val StateIconSize = 44.dp

/**
 * Failure state for any surface, with a retry affordance and an optional [secondaryAction] beside it.
 * A null [onRetry] leaves the retry out, for a failure that retrying cannot change.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FlowErrorState(
    error: String,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.Error,
    secondaryAction: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FlowStateIcon(icon = icon, tint = MaterialTheme.colorScheme.error)
            Text(
                text = error,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onRetry != null) {
                    Button(
                        onClick = onRetry,
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
                secondaryAction?.invoke()
            }
        }
    }
}

/**
 * A page YouTube refused with [message] in its own words, such as a deleted playlist. Retrying
 * cannot bring it back, so the only action is [onBack].
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FlowGoneState(
    message: String,
    icon: ImageVector,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowErrorState(
        error = message,
        onRetry = null,
        modifier = modifier,
        icon = icon,
        secondaryAction = {
            Button(
                onClick = onBack,
                shapes = ButtonDefaults.shapes(),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.back))
            }
        },
    )
}

/**
 * Empty state for any surface. [icon] and [subtitle] are optional so a bare
 * "nothing here yet" line stays a one-liner at the call site.
 */
@Composable
fun FlowEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (icon != null) {
                FlowStateIcon(icon = icon, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            action?.invoke()
        }
    }
}

/**
 * The icon of an empty, error or message state, on the state container shape.
 */
@Composable
fun FlowStateIcon(
    icon: ImageVector,
    tint: Color,
) {
    Surface(
        shape = flowActionShape(),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.size(StateIconContainerSize),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(StateIconSize),
                tint = tint,
            )
        }
    }
}
