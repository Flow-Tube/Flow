/*
 * Copyright (C) 2025-2026 Flow | A-EDev
 *
 * This file is part of Flow (https://github.com/Flow-Tube/Flow).
 */

package io.github.aedev.flow.ui.components.music.section

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.recommendation.MusicSection
import io.github.aedev.flow.innertube.pages.HomePage
import io.github.aedev.flow.ui.screens.music.MusicHomeChipPage
import io.github.aedev.flow.ui.screens.music.MusicHomeChipStatus
import io.github.aedev.flow.ui.screens.music.MusicUiState
import org.junit.Rule
import org.junit.Test

/**
 * InnerTube's home returns several sections under the same title, and the same video under more
 * than one of them. Every lazy key the feed emits must survive that: a collision throws
 * "Key ... was already used" from LazyColumn's measure pass and takes the whole screen down.
 */
class MusicHomeFeedKeyTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun track(id: String) =
        MusicTrack(
            videoId = id,
            title = "Track $id",
            artist = "Artist",
            thumbnailUrl = "",
            duration = 100,
        )

    private fun section(
        title: String,
        tracks: List<MusicTrack>,
    ) = MusicSection(
        title = title,
        tracks = tracks,
    )

    @Composable
    private fun Feed(uiState: MusicUiState) {
        val gridState = rememberLazyGridState()
        MaterialTheme {
            LazyColumn {
                musicHomeFeed(
                    uiState = uiState,
                    sectionOrder = HomeSectionType.values().toList(),
                    quickPickTracks = emptyList(),
                    speedDialTracks = emptyList(),
                    popularArtists = emptyList(),
                    library = MusicHomeLibrary(),
                    quickPicksGridState = gridState,
                    onSongClick = { _, _, _ -> },
                    onVideoClick = {},
                    onArtistClick = {},
                    onAlbumClick = {},
                    onMoodsClick = {},
                    onChipToggle = {},
                    onTrackMenu = {},
                    onCollectionMenu = {},
                    onLoadMore = {},
                    onChipRetry = {},
                    onChipLoadMore = {},
                )
            }
        }
    }

    @Test
    fun twoDynamicSectionsSharingATitleDoNotCollide() {
        val uiState =
            MusicUiState(
                dynamicSections =
                    listOf(
                        section("Today's hits", listOf(track("a"))),
                        section("Today's hits", listOf(track("b"))),
                    ),
            )

        composeRule.setContent { Feed(uiState) }

        composeRule.onNodeWithText("Today's hits").assertExists()
    }

    @Test
    fun twoSimilarToSectionsSharingATitleDoNotCollide() {
        val uiState =
            MusicUiState(
                similarToSections =
                    listOf(
                        section("Because you played", listOf(track("a"))),
                        section("Because you played", listOf(track("b"))),
                    ),
            )

        composeRule.setContent { Feed(uiState) }

        composeRule.onNodeWithText("Because you played").assertExists()
    }

    @Test
    fun aSectionRepeatingTheSameTrackDoesNotCollide() {
        val uiState =
            MusicUiState(
                dynamicSections = listOf(section("Repeats", listOf(track("dup"), track("dup")))),
            )

        composeRule.setContent { Feed(uiState) }

        composeRule.onNodeWithText("Repeats").assertExists()
    }

    @Test
    fun twoChipShelvesSharingATitleDoNotCollide() {
        val uiState =
            MusicUiState(
                homeChipPage =
                    MusicHomeChipPage(
                        chip = HomePage.Chip("Energize", null, null),
                        status = MusicHomeChipStatus.READY,
                        sections =
                            listOf(
                                section("Power boost", listOf(track("a"))),
                                section("Power boost", listOf(track("b"))),
                            ),
                    ),
            )

        composeRule.setContent { Feed(uiState) }

        composeRule.onNodeWithText("Power boost").assertExists()
    }
}
