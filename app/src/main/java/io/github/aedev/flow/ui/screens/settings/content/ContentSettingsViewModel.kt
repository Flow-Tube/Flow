package io.github.aedev.flow.ui.screens.settings.content

import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.SharedLinkAction
import io.github.aedev.flow.data.local.WatchedThreshold
import io.github.aedev.flow.ui.screens.settings.SettingsViewModel
import javax.inject.Inject

/** What shows up in the feeds: Home, Subscriptions, Shorts, Watch later, watched filtering, notes and sharing. */
@HiltViewModel
class ContentSettingsViewModel
    @Inject
    constructor(
        private val preferences: PlayerPreferences,
    ) : SettingsViewModel() {
        val homeFeed = preferences.homeFeedEnabled.asState(true)
        val refreshOnReselect = preferences.refreshHomeOnReselect.asState(true)
        val homeChips = preferences.homeChipsEnabled.asState(true)
        val homeNewVideosButton = preferences.homeNewVideosButtonEnabled.asState(true)
        val continueWatching = preferences.continueWatchingEnabled.asState(true)
        val homeShortsShelf = preferences.homeShortsShelfEnabled.asState(true)
        val homeSubscriptions = preferences.homeSubscriptionsEnabled.asState(true)
        val hideWatchedHome = preferences.hideWatchedVideosFromHome.asState(false)

        val subsVideos = preferences.subscriptionShowVideos.asState(true)
        val subsShorts = preferences.subscriptionShowShorts.asState(true)
        val subsShortsShelf = preferences.shortsShelfEnabled.asState(true)
        val subsLive = preferences.subscriptionShowLive.asState(true)
        val subsUpcoming = preferences.subscriptionShowUpcoming.asState(true)
        val subsPostsChip = preferences.subscriptionPostsChip.asState(true)
        val subsUngrouped = preferences.subscriptionUngroupedEnabled.asState(false)
        val subsCollaborations = preferences.subscriptionCollaborationsEnabled.asState(true)
        val hideWatchedSubs = preferences.hideWatchedVideosFromSubscriptions.asState(false)
        val hideUnplayableSubs = preferences.hideUnplayableVideosFromSubscriptions.asState(false)
        val subsRefreshOnStartup = preferences.subscriptionRefreshOnStartup.asState(false)
        val subsCheckedCount = preferences.subscriptionShowCheckedVideoCount.asState(true)

        val shortsContent = preferences.shortsContentEnabled.asState(true)
        val channelShortsTab = preferences.channelShortsTabWhenHidden.asState(false)
        val hideWatchedShorts = preferences.hideWatchedShorts.asState(true)
        val removeWatchedWatchLater = preferences.removeWatchedFromWatchLater.asState(false)
        val watchedThreshold = preferences.watchedThreshold.asState(WatchedThreshold.ALMOST_FINISHED)
        val sharedLinkAction = preferences.sharedLinkAction.asState(SharedLinkAction.ASK_WHILE_PLAYING)

        val notes = preferences.notesEnabled.asState(true)
        val channelNotes = preferences.channelNotesEnabled.asState(true)
        val videoNotes = preferences.videoNotesEnabled.asState(true)
        val shareWithoutText = preferences.shareWithoutText.asState(false)

        fun setHomeFeed(value: Boolean) = write { preferences.setHomeFeedEnabled(value) }

        fun setRefreshOnReselect(value: Boolean) = write { preferences.setRefreshHomeOnReselect(value) }

        fun setHomeChips(value: Boolean) = write { preferences.setHomeChipsEnabled(value) }

        fun setHomeNewVideosButton(value: Boolean) = write { preferences.setHomeNewVideosButtonEnabled(value) }

        fun setContinueWatching(value: Boolean) = write { preferences.setContinueWatchingEnabled(value) }

        fun setHomeShortsShelf(value: Boolean) = write { preferences.setHomeShortsShelfEnabled(value) }

        fun setHomeSubscriptions(value: Boolean) = write { preferences.setHomeSubscriptionsEnabled(value) }

        fun setHideWatchedHome(value: Boolean) = write { preferences.setHideWatchedVideosFromHome(value) }

        fun setSubsVideos(value: Boolean) = write { preferences.setSubscriptionShowVideos(value) }

        fun setSubsShorts(value: Boolean) = write { preferences.setSubscriptionShowShorts(value) }

        fun setSubsShortsShelf(value: Boolean) = write { preferences.setShortsShelfEnabled(value) }

        fun setSubsLive(value: Boolean) = write { preferences.setSubscriptionShowLive(value) }

        fun setSubsUpcoming(value: Boolean) = write { preferences.setSubscriptionShowUpcoming(value) }

        fun setSubsPostsChip(value: Boolean) = write { preferences.setSubscriptionPostsChip(value) }

        fun setSubsUngrouped(value: Boolean) = write { preferences.setSubscriptionUngroupedEnabled(value) }

        fun setSubsCollaborations(value: Boolean) = write { preferences.setSubscriptionCollaborationsEnabled(value) }

        fun setHideWatchedSubs(value: Boolean) = write { preferences.setHideWatchedVideosFromSubscriptions(value) }

        fun setHideUnplayableSubs(value: Boolean) = write { preferences.setHideUnplayableVideosFromSubscriptions(value) }

        fun setSubsRefreshOnStartup(value: Boolean) = write { preferences.setSubscriptionRefreshOnStartup(value) }

        fun setSubsCheckedCount(value: Boolean) = write { preferences.setSubscriptionShowCheckedVideoCount(value) }

        fun setShortsContent(value: Boolean) = write { preferences.setShortsContentEnabled(value) }

        fun setChannelShortsTab(value: Boolean) = write { preferences.setChannelShortsTabWhenHidden(value) }

        fun setHideWatchedShorts(value: Boolean) = write { preferences.setHideWatchedShorts(value) }

        fun setRemoveWatchedWatchLater(value: Boolean) = write { preferences.setRemoveWatchedFromWatchLater(value) }

        fun setWatchedThreshold(value: WatchedThreshold) = write { preferences.setWatchedThreshold(value) }

        fun setSharedLinkAction(value: SharedLinkAction) = write { preferences.setSharedLinkAction(value) }

        fun setNotes(value: Boolean) = write { preferences.setNotesEnabled(value) }

        fun setChannelNotes(value: Boolean) = write { preferences.setChannelNotesEnabled(value) }

        fun setVideoNotes(value: Boolean) = write { preferences.setVideoNotesEnabled(value) }

        fun setShareWithoutText(value: Boolean) = write { preferences.setShareWithoutText(value) }
    }
