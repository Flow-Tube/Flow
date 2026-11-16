package io.github.aedev.flow.ui.screens.settings.wellbeing

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

private const val MILLIS_PER_SECOND = 1_000L

/** Whole seconds left until [endsAt] (an elapsedRealtime), ticking once a second and stopping at zero. */
@Composable
internal fun rememberSecondsUntil(endsAt: Long): Int {
    var seconds by remember(endsAt) { mutableIntStateOf(secondsUntil(endsAt)) }
    LaunchedEffect(endsAt) {
        while (seconds > 0) {
            val leftMs = endsAt - SystemClock.elapsedRealtime()
            delay(leftMs % MILLIS_PER_SECOND + 1)
            seconds = secondsUntil(endsAt)
        }
    }
    return seconds
}

private fun secondsUntil(endsAt: Long): Int =
    ((endsAt - SystemClock.elapsedRealtime() + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).toInt().coerceAtLeast(0)
