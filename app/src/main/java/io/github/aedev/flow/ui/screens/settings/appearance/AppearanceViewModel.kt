package io.github.aedev.flow.ui.screens.settings.appearance

import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.local.AppFontPreferences
import io.github.aedev.flow.data.local.AppFontSelection
import io.github.aedev.flow.data.local.AppUiModePreferences
import io.github.aedev.flow.data.local.DEFAULT_NAV_TAB_ORDER
import io.github.aedev.flow.data.local.HomeFeedColumns
import io.github.aedev.flow.data.local.HomeViewMode
import io.github.aedev.flow.data.local.LocalDataManager
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.platform.AppIconController
import io.github.aedev.flow.platform.AppUiMode
import io.github.aedev.flow.ui.components.layout.navigation.NavigationVisibility
import io.github.aedev.flow.ui.screens.settings.SettingsViewModel
import io.github.aedev.flow.ui.theme.GridItemSize
import io.github.aedev.flow.ui.theme.ThemeMode
import io.github.aedev.flow.ui.theme.ThemeVariant
import io.github.aedev.flow.util.AppIcons
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** The Appearance page and its Navigation bar sub-page. */
@HiltViewModel
class AppearanceViewModel
    @Inject
    constructor(
        private val preferences: PlayerPreferences,
        private val uiModePreferences: AppUiModePreferences,
        private val appIconController: AppIconController,
        localDataManager: LocalDataManager,
        fontPreferences: AppFontPreferences,
    ) : SettingsViewModel() {
        val themeMode = localDataManager.themeMode.asState(ThemeMode.SYSTEM)
        val themeVariant = localDataManager.themeVariant.asState(ThemeVariant.DARK)
        val customThemeName = localDataManager.activeCustomTheme.map { it?.name }.asState(null)
        val interfaceMode = uiModePreferences.mode.asState(AppUiMode.AUTOMATIC)
        val font = fontPreferences.selection.asState(AppFontSelection())

        val homeViewMode = preferences.homeViewMode.asState(HomeViewMode.GRID)
        val homeColumns = preferences.homeFeedColumns.asState(HomeFeedColumns.AUTO)
        val gridItemSize =
            preferences.gridItemSize
                .map { raw -> GridItemSize.entries.firstOrNull { it.name == raw } ?: GridItemSize.BIG }
                .asState(GridItemSize.BIG)
        val libraryPreviews = preferences.libraryShelfPreviewsEnabled.asState(true)
        val separatePlaylists = preferences.separatePlaylistKinds.asState(false)
        val appLogo = preferences.showAppLogoIcon.asState(true)
        val groupBadges = preferences.showChannelGroupBadges.asState(false)
        val cardLikeButtons = preferences.videoCardActionsEnabled.asState(false)
        val cardMarkWatched = preferences.videoCardMarkWatchedEnabled.asState(false)
        val cardWatchProgress = preferences.showWatchProgress.asState(true)

        val shortsContent = preferences.shortsContentEnabled.asState(true)
        val navigationVisibility: StateFlow<NavigationVisibility> =
            combine(
                preferences.homeNavigationEnabled,
                preferences.shortsNavigationEnabled,
                preferences.musicNavigationEnabled,
                preferences.searchNavigationEnabled,
                preferences.categoriesNavigationEnabled,
            ) { home, shorts, music, search, categories ->
                NavigationVisibility(home = home, shorts = shorts, music = music, search = search, categories = categories)
            }.combine(preferences.downloadsNavigationEnabled) { visibility, downloads -> visibility.copy(downloads = downloads) }
                .asState(NavigationVisibility())
        val hideNavOnScroll = preferences.bottomNavHideOnScroll.asState(true)
        val navTabOrder = preferences.navTabOrder.asState(DEFAULT_NAV_TAB_ORDER)
        val defaultNavTabIndex = preferences.defaultNavTabIndex.asState(0)

        private val _appIcon = MutableStateFlow(AppIcons.DEFAULT_SUFFIX)
        val appIcon: StateFlow<String> = _appIcon.asStateFlow()

        init {
            write { _appIcon.value = appIconController.activeSuffix() }
        }

        fun setInterfaceMode(mode: AppUiMode) = write { uiModePreferences.setMode(mode) }

        fun setAppIcon(suffix: String) =
            write {
                appIconController.apply(suffix)
                _appIcon.value = suffix
            }

        fun setHomeViewMode(mode: HomeViewMode) = write { preferences.setHomeViewMode(mode) }

        fun setHomeColumns(columns: HomeFeedColumns) = write { preferences.setHomeFeedColumns(columns) }

        fun setGridItemSize(size: GridItemSize) = write { preferences.setGridItemSize(size.name) }

        fun setLibraryPreviews(enabled: Boolean) = write { preferences.setLibraryShelfPreviewsEnabled(enabled) }

        fun setSeparatePlaylists(enabled: Boolean) = write { preferences.setSeparatePlaylistKinds(enabled) }

        fun setAppLogo(enabled: Boolean) = write { preferences.setShowAppLogoIcon(enabled) }

        fun setGroupBadges(enabled: Boolean) = write { preferences.setShowChannelGroupBadges(enabled) }

        fun setCardLikeButtons(enabled: Boolean) = write { preferences.setVideoCardActionsEnabled(enabled) }

        fun setCardMarkWatched(enabled: Boolean) = write { preferences.setVideoCardMarkWatchedEnabled(enabled) }

        fun setCardWatchProgress(enabled: Boolean) = write { preferences.setShowWatchProgress(enabled) }

        fun setHomeTab(enabled: Boolean) = write { preferences.setHomeNavigationEnabled(enabled) }

        fun setShortsTab(enabled: Boolean) = write { preferences.setShortsNavigationEnabled(enabled) }

        fun setMusicTab(enabled: Boolean) = write { preferences.setMusicNavigationEnabled(enabled) }

        fun setSearchTab(enabled: Boolean) = write { preferences.setSearchNavigationEnabled(enabled) }

        fun setExploreTab(enabled: Boolean) = write { preferences.setCategoriesNavigationEnabled(enabled) }

        fun setDownloadsTab(enabled: Boolean) = write { preferences.setDownloadsNavigationEnabled(enabled) }

        fun setHideNavOnScroll(enabled: Boolean) = write { preferences.setBottomNavHideOnScroll(enabled) }

        fun setNavTabOrder(order: List<Int>) = write { preferences.setNavTabOrder(order) }

        fun setDefaultNavTab(index: Int) = write { preferences.setDefaultNavTabIndex(index) }
    }
