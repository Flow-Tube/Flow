package io.github.aedev.flow.ui.screens.settings.wellbeing

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.R
import io.github.aedev.flow.data.wellbeing.DailyLimitPolicy
import io.github.aedev.flow.data.wellbeing.DailyLimitStore
import io.github.aedev.flow.data.wellbeing.DailyTally
import io.github.aedev.flow.notification.DailyLimitGuard
import io.github.aedev.flow.ui.components.stats.spentTimeLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

private val ContentMaxWidth = 420.dp
private val IconContainerSize = 88.dp
private val IconSize = 44.dp
private const val MILLIS_PER_MINUTE = 60_000L

/** Today's count and the limit it ran into, as the Time's up page shows them. */
data class TimesUpState(
    val playedMs: Long,
    val limitMinutes: Int,
)

@HiltViewModel
class TimesUpViewModel
    @Inject
    constructor(
        private val guard: DailyLimitGuard,
        private val store: DailyLimitStore,
    ) : ViewModel() {
        /** When Keep watching becomes available, as an elapsedRealtime. Kept here so turning the phone doesn't restart it. */
        val keepWatchingAt: Long = SystemClock.elapsedRealtime() + DailyLimitPolicy.WAIT_MS

        private val _state = MutableStateFlow<TimesUpState?>(null)
        val state: StateFlow<TimesUpState?> = _state.asStateFlow()

        init {
            viewModelScope.launch {
                val today: DailyTally = guard.today()
                _state.value = TimesUpState(today.playedMs, store.settings.first().minutes)
            }
        }

        fun keepWatching(onDone: () -> Unit) {
            viewModelScope.launch {
                guard.extend()
                onDone()
            }
        }
    }

/** Shown when the daily limit runs out: today's time, Close, and Keep watching once the wait has passed. */
@Composable
internal fun TimesUpScreen(
    onClose: () -> Unit,
    viewModel: TimesUpViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val secondsLeft = rememberSecondsUntil(viewModel.keepWatchingAt)
    BackHandler(onBack = onClose)

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(IconContainerSize),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.HourglassBottom, contentDescription = null, modifier = Modifier.size(IconSize))
                    }
                }
                Text(
                    text = stringResource(R.string.daily_limit_reached_title),
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                )
                state?.let { shown ->
                    Text(
                        text =
                            stringResource(
                                R.string.times_up_body,
                                spentTimeLabel(shown.playedMs),
                                spentTimeLabel(shown.limitMinutes * MILLIS_PER_MINUTE),
                            ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(stringResource(R.string.close))
                }
                OutlinedButton(
                    onClick = { viewModel.keepWatching(onClose) },
                    enabled = secondsLeft == 0,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (secondsLeft == 0) {
                            stringResource(R.string.times_up_keep_watching)
                        } else {
                            stringResource(R.string.times_up_keep_watching_wait, secondsLeft)
                        },
                    )
                }
                Text(
                    text = stringResource(R.string.times_up_keep_watching_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
