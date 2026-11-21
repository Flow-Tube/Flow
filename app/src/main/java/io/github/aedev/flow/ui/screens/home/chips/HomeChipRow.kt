package io.github.aedev.flow.ui.screens.home.chips

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.shared.FlowFilterChip

private val ChipRowHeight = 48.dp
private val ChipRowPadding = 12.dp
private val ChipSpacing = 8.dp

/** The chips above the feed. They ride in Home's scroll-away header, so they move with the top bar. */
@Composable
internal fun HomeChipRow(
    state: HomeChipsState,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier =
            modifier
                .fillMaxWidth()
                .height(ChipRowHeight),
        contentPadding = PaddingValues(horizontal = ChipRowPadding),
        horizontalArrangement = Arrangement.spacedBy(ChipSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(state.chips, key = { it.key }) { chip ->
            val label = chip.label()
            val description = stringResource(R.string.home_chip_description, label)
            FlowFilterChip(
                label = label,
                selected = chip.key == state.selected,
                onClick = { onSelect(chip.key) },
                modifier = Modifier.semantics { contentDescription = description },
            )
        }
    }
}

/** The name of a theme chip's YouTube category ([io.github.aedev.flow.data.recommendation.NeuroCategories]). */
@StringRes
private fun themeLabel(category: String): Int? =
    when (category) {
        "music" -> R.string.category_music
        "gaming" -> R.string.category_gaming
        "sports" -> R.string.category_sports
        "education" -> R.string.category_education
        "news" -> R.string.category_news
        "science" -> R.string.home_theme_science
        "comedy" -> R.string.home_theme_comedy
        "film" -> R.string.home_theme_film
        "autos" -> R.string.home_theme_autos
        "pets" -> R.string.home_theme_pets
        "travel" -> R.string.home_theme_travel
        "howto" -> R.string.home_theme_howto
        "nonprofits" -> R.string.home_theme_nonprofits
        else -> null
    }

@Composable
private fun HomeChip.label(): String =
    when (this) {
        HomeChip.All -> stringResource(R.string.home_chip_all)
        is HomeChip.Interest -> interest.category?.let(::themeLabel)?.let { stringResource(it) } ?: interest.label
        HomeChip.NewToYou -> stringResource(R.string.home_chip_new_to_you)
        HomeChip.RecentlyUploaded -> stringResource(R.string.home_chip_recently_uploaded)
        HomeChip.Mixes -> stringResource(R.string.home_chip_mixes)
        HomeChip.Live -> stringResource(R.string.home_chip_live)
        HomeChip.Watched -> stringResource(R.string.home_chip_watched)
    }
