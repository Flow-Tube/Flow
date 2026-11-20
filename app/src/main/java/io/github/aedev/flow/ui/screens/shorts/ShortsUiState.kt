package io.github.aedev.flow.ui.screens.shorts

import io.github.aedev.flow.data.model.ShortVideo

data class ShortsUiState(
    val shorts: List<ShortVideo> = emptyList(),
    val currentIndex: Int = 0,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
)
