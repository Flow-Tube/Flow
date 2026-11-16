/*
 * Copyright (C) 2025-2026 Flow | A-EDev
 *
 * This file is part of Flow (https://github.com/Flow-Tube/Flow).
 */

package io.github.aedev.flow.ui.components.shared

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow

/**
 * The single filter chip for every surface: home feed chips, search filters, browse tabs,
 * library content filters. Selection shows through the shape and the fill, with no check, so a
 * chip keeps its width when it is picked.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FlowFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        shapes = FilterChipDefaults.shapes(),
        modifier = modifier,
    )
}

/**
 * A filter chip that opens a menu instead of toggling. [selected] marks that a non-default option is
 * active; the chevron tells it apart from a chip that toggles.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FlowDropdownFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        shapes = FilterChipDefaults.shapes(),
        modifier = modifier,
        trailingIcon = {
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(FilterChipDefaults.IconSize),
            )
        },
    )
}
