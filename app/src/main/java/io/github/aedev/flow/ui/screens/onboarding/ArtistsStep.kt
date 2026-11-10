package io.github.aedev.flow.ui.screens.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.recommendation.music.FavouriteArtist
import io.github.aedev.flow.data.recommendation.music.FavouriteArtistPicker
import io.github.aedev.flow.ui.components.shared.FlowSearchField
import io.github.aedev.flow.ui.components.shared.FlowSectionHeader
import io.github.aedev.flow.ui.components.shared.FlowSegmentedGap
import io.github.aedev.flow.ui.components.shared.FlowSpinner
import io.github.aedev.flow.ui.components.shared.MediaArtistToggleRow
import io.github.aedev.flow.ui.components.shared.dismissKeyboardOnPress
import io.github.aedev.flow.ui.components.shared.flowSegmentShape

private val SectionTopPadding = 16.dp

/** Optional: artists the music recommendations start from, saved the moment one is picked. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ArtistsStep(
    picker: FavouriteArtistPicker,
    header: LazyListScope.() -> Unit,
    contentPadding: PaddingValues,
) {
    val state by picker.state.collectAsStateWithLifecycle()
    val query by picker.query.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val pickedIds = state.pickedIds
    LazyColumn(
        modifier = Modifier.fillMaxSize().dismissKeyboardOnPress { focusManager.clearFocus() },
        contentPadding = contentPadding,
    ) {
        header()
        item(key = "search") {
            FlowSearchField(
                query = query,
                onQueryChange = picker::onQueryChange,
                placeholder = stringResource(R.string.onboarding_artists_search_placeholder),
                modifier = Modifier.fillMaxWidth(),
                onSearch = { focusManager.clearFocus() },
                onClear = { picker.onQueryChange("") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            )
        }
        artistSection("picked", R.string.favourite_artists_picked, state.picked, pickedIds, picker::setFavourite)
        val candidates = state.candidates
        if (candidates == null) {
            item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(top = SectionTopPadding), contentAlignment = Alignment.Center) { FlowSpinner() }
            }
        } else {
            artistSection(
                key = "candidates",
                header = if (state.searching) R.string.favourite_artists_results else R.string.favourite_artists_popular,
                artists = candidates.filterNot { it.id in pickedIds },
                pickedIds = pickedIds,
                onToggle = picker::setFavourite,
            )
        }
    }
}

private fun LazyListScope.artistSection(
    key: String,
    header: Int,
    artists: List<FavouriteArtist>,
    pickedIds: Set<String>,
    onToggle: (FavouriteArtist, Boolean) -> Unit,
) {
    if (artists.isEmpty()) return
    item(key = "$key-header") {
        FlowSectionHeader(stringResource(header), modifier = Modifier.padding(top = SectionTopPadding))
    }
    itemsIndexed(artists, key = { _, artist -> "$key:${artist.id}" }) { index, artist ->
        MediaArtistToggleRow(
            name = artist.name,
            thumbnailUrl = artist.thumbnailUrl,
            picked = artist.id in pickedIds,
            shape = flowSegmentShape(index, artists.size),
            onToggle = { onToggle(artist, it) },
            modifier = Modifier.animateItem().padding(bottom = FlowSegmentedGap),
        )
    }
}
