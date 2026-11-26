package io.github.aedev.flow.ui.components.music.section

import androidx.annotation.StringRes
import io.github.aedev.flow.R

/**
 * Every music home section the viewer can hide. The names are stored, so they must never be
 * renamed; the feed sections keep the names of their [HomeSectionType].
 */
enum class MusicHomeShelf(
    @param:StringRes val labelRes: Int,
) {
    YOUR_PLAYLISTS(R.string.music_home_your_playlists),
    LIKED_MUSIC(R.string.liked_music_playlist),
    YOUR_SUBSCRIPTIONS(R.string.music_home_your_subscriptions),
    LISTEN_AGAIN(R.string.section_listen_again),
    ON_REPEAT(R.string.section_on_repeat),
    ROTATION(R.string.music_home_shelf_rotation),
    SPEED_DIAL(R.string.section_speed_dial),
    REDISCOVER(R.string.section_rediscover),
    DEEP_CUTS(R.string.section_deep_cuts),
    ARTISTS_FOR_YOU(R.string.section_artists_for_you),
    DAILY_DISCOVER(R.string.section_daily_discover),
    QUICK_PICKS(R.string.section_quick_picks),
    FROM_COMMUNITY(R.string.section_from_the_community),
    RECOMMENDED(R.string.section_recommended),
    SIMILAR_TO(R.string.music_home_shelf_similar),
    LIVE_PERFORMANCES(R.string.section_live_performances),
    MUSIC_VIDEOS_FOR_YOU(R.string.section_music_videos_for_you),
    MUSIC_VIDEOS(R.string.section_music_videos),
    GENRES(R.string.music_home_shelf_genres),
    DYNAMIC_HOME(R.string.music_home_shelf_more),
    TOP_ALBUMS(R.string.section_top_albums),
    FAVORITE_ARTIST_ALBUMS(R.string.section_from_artists_you_love),
    NEW_RELEASES(R.string.section_new_releases),
    CHARTS(R.string.music_home_shelf_charts),
    POPULAR_ARTISTS(R.string.section_popular_artists),
    MIXED_FOR_YOU(R.string.section_mixed_for_you),
    MOODS_AND_GENRES(R.string.section_mood_and_genres),
    LASTFM_DISCOVER(R.string.music_home_lastfm_discover),
    ;

    companion object {
        fun of(type: HomeSectionType): MusicHomeShelf = valueOf(type.name)

        fun fromStored(names: Set<String>): Set<MusicHomeShelf> = entries.filterTo(HashSet()) { it.name in names }
    }
}
