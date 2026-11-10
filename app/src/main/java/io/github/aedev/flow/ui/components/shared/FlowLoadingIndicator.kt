/*
 * Copyright (C) 2025-2026 Flow | A-EDev
 *
 * This file is part of Flow (https://github.com/Flow-Tube/Flow).
 */

package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LoadingIndicatorDefaults
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.unit.dp

/** The Appearance switch that swaps the M3 Expressive loading indicator for the circular one. */
val LocalClassicLoadingIndicator = staticCompositionLocalOf { false }

/**
 * The app's indeterminate loading mark: the M3 Expressive [LoadingIndicator], or the circular
 * progress indicator while [LocalClassicLoadingIndicator] is on.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FlowSpinner(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    if (LocalClassicLoadingIndicator.current) {
        CircularProgressIndicator(modifier = modifier, color = color.takeOrElse { ProgressIndicatorDefaults.circularColor })
    } else {
        LoadingIndicator(modifier = modifier, color = color.takeOrElse { LoadingIndicatorDefaults.indicatorColor })
    }
}

/**
 * Full-surface loading state. Only ever composed while a surface has nothing else to show, so the
 * indicator runs while it is on screen and leaves the tree with it.
 */
@Composable
fun FlowLoadingIndicator(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        FlowSpinner()
    }
}

/**
 * Inline loading row for a feed continuation or a paged list tail.
 */
@Composable
fun FlowFeedProgress(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        FlowSpinner()
    }
}
