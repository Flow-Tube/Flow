package io.github.aedev.flow.ui.screens.search

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.mutableStateOf
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.HomeFeedColumns
import io.github.aedev.flow.innertube.pages.search.SearchSuggestion
import io.github.aedev.flow.ui.components.search.SUGGESTION_TIMEOUT_MS
import io.github.aedev.flow.ui.components.search.collectLatestSuggestions
import io.github.aedev.flow.ui.components.shared.DeepFlowState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchScreenStateTest {
    private fun TestScope.state(
        suggestionsEnabled: Boolean = true,
        fetch: suspend (String) -> List<SearchSuggestion>?,
    ) = SearchScreenState(
        textFieldState = TextFieldState(),
        submittedQuery = null,
        scope = this,
        allHistoryState = mutableStateOf(emptyList()),
        suggestionsEnabledState = mutableStateOf(suggestionsEnabled),
        gridModeState = mutableStateOf(false),
        shortsEnabledState = mutableStateOf(true),
        feedColumnsState = mutableStateOf(HomeFeedColumns.AUTO),
        deepFlowState = mutableStateOf(DeepFlowState()),
        setDeepFlow = {},
        fetchSuggestions = fetch,
    )

    private fun rows(vararg texts: String) = texts.map { SearchSuggestion(it) }

    @Test
    fun `a newer keystroke cancels the request still out for the older one`() =
        runTest {
            val firstCancelled = CompletableDeferred<Unit>()
            val state =
                state { typed ->
                    if (typed == "lofi g") {
                        try {
                            awaitCancellation()
                        } finally {
                            firstCancelled.complete(Unit)
                        }
                    } else {
                        rows("lofi girl", "lofi girl radio")
                    }
                }
            val typed = MutableSharedFlow<String>()
            val job = launch { typed.collectLatestSuggestions(debounceMs = 150) { state.refreshSuggestions(it) } }
            runCurrent()

            typed.emit("lofi g")
            advanceTimeBy(200)
            typed.emit("lofi girl")
            advanceTimeBy(200)
            runCurrent()

            assertThat(firstCancelled.isCompleted).isTrue()
            assertThat(state.suggestions.map { it.text }).containsExactly("lofi girl", "lofi girl radio").inOrder()
            job.cancel()
        }

    @Test
    fun `keystrokes inside the debounce send one request`() =
        runTest {
            val asked = mutableListOf<String>()
            val state =
                state {
                    asked += it
                    rows(it)
                }
            val typed = MutableSharedFlow<String>()
            val job = launch { typed.collectLatestSuggestions(debounceMs = 150) { state.refreshSuggestions(it) } }
            runCurrent()

            typed.emit("lo")
            advanceTimeBy(50)
            typed.emit("lof")
            advanceTimeBy(50)
            typed.emit("lofi")
            advanceTimeBy(200)
            runCurrent()

            assertThat(asked).containsExactly("lofi")
            job.cancel()
        }

    @Test
    fun `a space after a word does not ask for that word again`() =
        runTest {
            val asked = mutableListOf<String>()
            val state =
                state {
                    asked += it
                    rows(it)
                }
            val typed = MutableSharedFlow<String>()
            val job = launch { typed.collectLatestSuggestions(debounceMs = 150) { state.refreshSuggestions(it) } }
            runCurrent()

            typed.emit("lofi")
            advanceTimeBy(200)
            typed.emit("lofi ")
            advanceTimeBy(200)
            runCurrent()

            assertThat(asked).containsExactly("lofi")
            job.cancel()
        }

    @Test
    fun `a failed request keeps the rows already on screen`() =
        runTest {
            var answer: List<SearchSuggestion>? = rows("lofi girl")
            val state = state { answer }
            state.refreshSuggestions("lofi")
            answer = null

            state.refreshSuggestions("lofi g")

            assertThat(state.suggestions.map { it.text }).containsExactly("lofi girl")
        }

    @Test
    fun `a request slower than the cap keeps the rows already on screen`() =
        runTest {
            var hang = false
            val state = state { if (hang) awaitCancellation() else rows("lofi girl") }
            state.refreshSuggestions("lofi")
            hang = true

            val job = launch { state.refreshSuggestions("lofi gi") }
            advanceTimeBy(SUGGESTION_TIMEOUT_MS + 1)
            runCurrent()

            assertThat(job.isCompleted).isTrue()
            assertThat(state.suggestions.map { it.text }).containsExactly("lofi girl")
        }

    @Test
    fun `short text and disabled suggestions clear the rows without asking`() =
        runTest {
            var asked = 0
            val state =
                state {
                    asked++
                    rows("x")
                }
            state.refreshSuggestions(" l ")
            assertThat(state.suggestions).isEmpty()

            val disabled =
                state(suggestionsEnabled = false) {
                    asked++
                    rows("x")
                }
            disabled.refreshSuggestions("lofi")
            assertThat(disabled.suggestions).isEmpty()
            assertThat(asked).isEqualTo(0)
        }
}
