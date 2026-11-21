/*
 * Copyright (C) 2025-2026 Flow | A-EDev
 *
 * This file is part of Flow (https://github.com/Flow-Tube/Flow).
 */

package io.github.aedev.flow.ui.components.music.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowOutward
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.innertube.YouTube.SearchFilter
import io.github.aedev.flow.innertube.pages.SearchSummary
import io.github.aedev.flow.innertube.pages.SearchSummaryKind
import io.github.aedev.flow.ui.components.shared.FlowFilterChip
import io.github.aedev.flow.ui.components.shared.FlowSearchField
import io.github.aedev.flow.ui.theme.Dimensions

/**
 * The music search bar, laid out like the Search tab's: back and mic sit outside the field, and the
 * field itself is the shared pill carrying only the query and its clear button.
 */
@Composable
fun MusicSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onBackClick: () -> Unit,
    onClearClick: () -> Unit,
    onVoiceSearchClick: (() -> Unit)?,
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = BarHorizontalPadding, vertical = BarVerticalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BarItemSpacing),
        ) {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.btn_back),
                )
            }

            FlowSearchField(
                query = query,
                onQueryChange = onQueryChange,
                placeholder = stringResource(R.string.search_music_placeholder),
                modifier = Modifier.weight(1f),
                onSearch = onSearch,
                onClear = onClearClick,
                focusRequester = focusRequester,
            )

            if (onVoiceSearchClick != null) {
                IconButton(onClick = onVoiceSearchClick) {
                    Icon(
                        imageVector = Icons.Rounded.Mic,
                        contentDescription = stringResource(R.string.voice_search_cd),
                    )
                }
            }
        }
    }
}

@Composable
fun SearchFilterChips(
    filters: List<SearchFilter>,
    activeFilter: SearchFilter?,
    onFilterClick: (SearchFilter?) -> Unit,
) {
    val listState = rememberLazyListState()
    // A lazy row keeps its first visible key when the list changes, which would hide the chips
    // YouTube puts before it once its own list replaces the default one.
    LaunchedEffect(filters) { listState.requestScrollToItem(0) }
    LazyRow(
        state = listState,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = Dimensions.ContentPaddingHorizontal),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(filters, key = { it.value }) { filter ->
            val selected = activeFilter == filter
            FlowFilterChip(
                label = filter.label(),
                selected = selected,
                onClick = { onFilterClick(if (selected) null else filter) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SearchSuggestionRow(
    suggestion: String,
    onClick: () -> Unit,
) {
    ListItem(
        onClick = onClick,
        leadingContent = {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
            )
        },
        trailingContent = {
            Icon(
                imageVector = Icons.Rounded.ArrowOutward,
                contentDescription = null,
            )
        },
    ) {
        Text(
            text = suggestion,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val BarHorizontalPadding = 4.dp
private val BarVerticalPadding = 4.dp
private val BarItemSpacing = 2.dp

/** A search section's heading: our own words for the grouped kinds, the server's for a shelf it titled. */
@Composable
fun searchSummaryTitle(summary: SearchSummary): String =
    when (summary.kind) {
        SearchSummaryKind.TOP_RESULT -> stringResource(R.string.section_top_result)
        SearchSummaryKind.SONGS -> stringResource(R.string.filter_songs)
        SearchSummaryKind.VIDEOS -> stringResource(R.string.tab_videos)
        SearchSummaryKind.EPISODES -> stringResource(R.string.music_search_section_episodes)
        SearchSummaryKind.ALBUMS -> stringResource(R.string.filter_albums)
        SearchSummaryKind.ARTISTS -> stringResource(R.string.music_search_section_artists)
        SearchSummaryKind.PLAYLISTS -> stringResource(R.string.music_search_section_playlists)
        SearchSummaryKind.SHELF -> summary.title
    }
