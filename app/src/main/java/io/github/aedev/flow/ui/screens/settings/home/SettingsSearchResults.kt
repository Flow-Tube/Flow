package io.github.aedev.flow.ui.screens.settings.home

import android.content.res.Resources
import android.os.Build
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.BuildConfig
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.settings.LocalNewSettings
import io.github.aedev.flow.ui.components.settings.SettingEntry
import io.github.aedev.flow.ui.components.settings.SettingsDestination
import io.github.aedev.flow.ui.components.settings.SettingsListScope
import io.github.aedev.flow.ui.components.shared.FlowEmptyState
import io.github.aedev.flow.ui.components.shared.FlowNavRow
import io.github.aedev.flow.ui.components.shared.LocalDpadNavigation
import io.github.aedev.flow.ui.screens.settings.index.DestinationIndex
import io.github.aedev.flow.ui.screens.settings.index.SearchableSetting
import io.github.aedev.flow.ui.screens.settings.index.SettingsIndex

private const val BREADCRUMB_SEPARATOR = " › "

/** Every option this build and device can show, with its strings resolved for the current locale. */
@Composable
internal fun rememberSearchableSettings(): List<SearchableSetting> {
    val resources = LocalContext.current.resources
    val configuration = LocalConfiguration.current
    val onTv = LocalDpadNavigation.current
    return remember(configuration, onTv) {
        SettingsIndex.all
            .filter { it.availability.isAvailable(githubFeatures = BuildConfig.UPDATER_ENABLED, sdk = Build.VERSION.SDK_INT, tv = onTv) }
            .filter { DestinationIndex.isReachable(it, onTv) }
            .map { it.resolve(resources) }
    }
}

private fun SettingEntry.resolve(resources: Resources): SearchableSetting {
    val pageTitles =
        destination.path
            .filter { it != SettingsDestination.HOME }
            .map { resources.getString(it.titleRes) }
    val sectionTitle = section?.let(resources::getString)
    val crumbs = (pageTitles + listOfNotNull(sectionTitle)).ifEmpty { listOf(resources.getString(R.string.settings_title)) }
    return SearchableSetting(
        entry = this,
        title = resources.getString(title),
        summary = summary?.let(resources::getString),
        keywords =
            keywords
                ?.let(resources::getString)
                ?.split(',')
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                .orEmpty(),
        breadcrumb = crumbs.joinToString(BREADCRUMB_SEPARATOR),
    )
}

/** The ranked results for a settings search, or an empty state when nothing matches. */
internal fun SettingsListScope.searchResults(
    query: String,
    results: List<SearchableSetting>,
    onResultClick: (SettingEntry) -> Unit,
) {
    if (results.isEmpty()) {
        item("search#empty") {
            FlowEmptyState(
                title = stringResource(R.string.settings_search_no_results, query),
                icon = Icons.Outlined.SearchOff,
            )
        }
        return
    }
    group(key = "search#results") {
        results.forEach { result ->
            row(result.entry.key) { shape ->
                FlowNavRow(
                    title = result.title,
                    supportingText = result.breadcrumb,
                    onClick = { onResultClick(result.entry) },
                    shape = shape,
                    titleBadge = result.entry.key in LocalNewSettings.current,
                )
            }
        }
    }
}
