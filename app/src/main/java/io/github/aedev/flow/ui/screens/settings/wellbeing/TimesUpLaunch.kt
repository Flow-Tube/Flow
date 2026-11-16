package io.github.aedev.flow.ui.screens.settings.wellbeing

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.notification.DailyLimitGuard
import javax.inject.Inject

const val TIMES_UP_ROUTE = "times_up"

@HiltViewModel
class TimesUpLaunchViewModel
    @Inject
    constructor(
        private val guard: DailyLimitGuard,
    ) : ViewModel() {
        val pageRequested = guard.pageRequested

        fun consume() = guard.consumePageRequest()
    }

/**
 * Opens the Time's up page when the daily limit runs out, or the next time Flow is on screen when it ran out in the
 * background or in a car. [enabled] holds it back during onboarding and picture-in-picture.
 */
@Composable
fun TimesUpLaunchEffect(
    enabled: Boolean,
    onOpen: () -> Unit,
) {
    val activity = LocalContext.current as? ComponentActivity ?: return
    val viewModel: TimesUpLaunchViewModel = hiltViewModel(activity)
    val requested by viewModel.pageRequested.collectAsStateWithLifecycle()
    val open by rememberUpdatedState(onOpen)
    LaunchedEffect(requested, enabled) {
        if (requested && enabled) {
            viewModel.consume()
            open()
        }
    }
}
