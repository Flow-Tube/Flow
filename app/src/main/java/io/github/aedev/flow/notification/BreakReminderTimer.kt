package io.github.aedev.flow.notification

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.LocalDataManager
import io.github.aedev.flow.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reminds the viewer to take a break after the chosen time with Flow on screen. It only runs while Flow is on screen, so
 * nothing wakes the device when it isn't: leaving cancels the one pending delay, and coming back waits out the rest.
 */
@Singleton
class BreakReminderTimer
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val settings: LocalDataManager,
        @ApplicationScope private val scope: CoroutineScope,
    ) : DefaultLifecycleObserver {
        private data class BreakSettings(
            val enabled: Boolean,
            val minutes: Int,
        )

        private val clock = BreakClock()
        private val onScreen = MutableStateFlow(false)
        private var countedFor: BreakSettings? = null

        fun install() {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
            // The main thread, because the lifecycle callbacks below share the clock with this collector.
            scope.launch(Dispatchers.Main.immediate) {
                onScreen.collectLatest { shown -> if (shown) remindWhileOnScreen() }
            }
        }

        private suspend fun remindWhileOnScreen() {
            combine(settings.breakReminder, settings.breakFrequency, ::BreakSettings)
                .distinctUntilChanged()
                .collectLatest { current ->
                    // Turning the reminder on after a long session must not fire at once, so new settings count afresh.
                    if (current != countedFor) {
                        countedFor = current
                        clock.restart(now())
                    }
                    if (!current.enabled) return@collectLatest
                    val frequencyMs = TimeUnit.MINUTES.toMillis(current.minutes.toLong())
                    while (true) {
                        delay(clock.remainingMs(frequencyMs, now()))
                        clock.restart(now())
                        remind()
                    }
                }
        }

        private suspend fun remind() =
            withContext(Dispatchers.IO) {
                NotificationHelper.showReminderNotification(
                    context,
                    context.getString(R.string.reminder_break_title),
                    context.getString(R.string.reminder_break_body),
                )
            }

        override fun onStart(owner: LifecycleOwner) {
            clock.onShown(now())
            onScreen.value = true
        }

        override fun onStop(owner: LifecycleOwner) {
            clock.onHidden(now())
            onScreen.value = false
        }

        private fun now(): Long = SystemClock.elapsedRealtime()
    }
