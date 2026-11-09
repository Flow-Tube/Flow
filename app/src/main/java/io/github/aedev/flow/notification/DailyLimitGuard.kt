package io.github.aedev.flow.notification

import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.util.UnstableApi
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.R
import io.github.aedev.flow.data.wellbeing.DailyLimitPolicy
import io.github.aedev.flow.data.wellbeing.DailyLimitSettings
import io.github.aedev.flow.data.wellbeing.DailyLimitStore
import io.github.aedev.flow.data.wellbeing.DailyTally
import io.github.aedev.flow.di.ApplicationScope
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.shorts.ShortsPlayerPool
import io.github.aedev.flow.service.MusicServiceState
import io.github.aedev.flow.ui.screens.settings.wellbeing.millisUntilNextDay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Counts today's playing time toward the daily limit and pauses playback when it runs out. It follows the players'
 * playing state and keeps one delay pending while something plays, for the warning, the limit or midnight, whichever
 * comes first; nothing runs while nothing plays. Playing time is measured with elapsedRealtime; the day it lands on
 * comes from the clock, which only ever moves the count forward a day. The players are only reached while the limit is on.
 */
@Singleton
@OptIn(UnstableApi::class)
class DailyLimitGuard
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val store: DailyLimitStore,
        @ApplicationScope private val scope: CoroutineScope,
        private val videoPlayer: Lazy<EnhancedPlayerManager>,
        private val shortsPlayers: Lazy<ShortsPlayerPool>,
    ) {
        private val _pageRequested = MutableStateFlow(false)

        /** The Time's up page should open the next time Flow is on screen. */
        val pageRequested: StateFlow<Boolean> = _pageRequested.asStateFlow()

        private var tally: DailyTally? = null

        fun install() {
            // The main thread, so the players are paused on their own thread and the tally has one writer.
            scope.launch(Dispatchers.Main.immediate) {
                store.settings.distinctUntilChanged().collectLatest { limit ->
                    if (!limit.enabled) return@collectLatest
                    playing(limit.countMusic).distinctUntilChanged().collectLatest { playing ->
                        if (playing) countWhilePlaying(limit)
                    }
                }
            }
        }

        fun consumePageRequest() {
            _pageRequested.value = false
        }

        /** Today's counted time, with the stored day rolled over to today. */
        suspend fun today(): DailyTally = withContext(Dispatchers.Main.immediate) { current() }

        /** Keep watching: exactly fifteen more minutes from now. */
        suspend fun extend() =
            withContext(Dispatchers.Main.immediate) {
                val limit = store.settings.first()
                save(DailyLimitPolicy.extend(limit.minutes, current()))
            }

        private fun playing(countMusic: Boolean): Flow<Boolean> {
            val video = videoPlayer.get().playerState.map { it.isPlaying }
            val music = if (countMusic) EnhancedMusicPlayerManager.playerState.map { it.isPlaying } else flowOf(false)
            return combine(video, shortsPlayers.get().anyPlaying, music) { v, s, m -> v || s || m }
        }

        private suspend fun countWhilePlaying(limit: DailyLimitSettings) {
            var since = now()
            try {
                while (true) {
                    val counted = current()
                    if (DailyLimitPolicy.remainingMs(limit.minutes, counted) == 0L) {
                        reachLimit(limit, counted)
                        awaitCancellation()
                    }
                    if (DailyLimitPolicy.shouldWarn(limit.minutes, counted)) warn(counted)
                    val untilMidnight = millisUntilNextDay(ZonedDateTime.now())
                    delay(minOf(DailyLimitPolicy.msUntilNextCheck(limit.minutes, counted), untilMidnight + MIDNIGHT_SLACK_MS))
                    since = addPlayed(since)
                }
            } finally {
                withContext(NonCancellable) { addPlayed(since) }
            }
        }

        /** Books the time played since [since] on the day it was played, and returns the new start. */
        private suspend fun addPlayed(since: Long): Long {
            val at = now()
            val counted = tally ?: current()
            save(DailyLimitPolicy.add(counted, at - since))
            return at
        }

        private suspend fun reachLimit(
            limit: DailyLimitSettings,
            counted: DailyTally,
        ) {
            if (!counted.reached) save(counted.copy(reached = true))
            _pageRequested.value = true
            // Pausing on the road could matter for safety; the page waits for the phone instead.
            if (MusicServiceState.isCarConnected) return
            videoPlayer.get().pause()
            shortsPlayers.get().pauseAll()
            if (limit.countMusic) EnhancedMusicPlayerManager.pause()
            if (!isOnScreen()) {
                notify(R.string.daily_limit_reached_title, R.string.daily_limit_reached_body)
            }
        }

        private suspend fun warn(counted: DailyTally) {
            save(counted.copy(warned = true))
            notify(R.string.daily_limit_warning_title, R.string.daily_limit_warning_body)
        }

        private suspend fun notify(
            title: Int,
            body: Int,
        ) = withContext(Dispatchers.IO) {
            NotificationHelper.showReminderNotification(context, context.getString(title), context.getString(body))
        }

        private suspend fun current(): DailyTally {
            val stored = tally ?: store.tally.first()
            val today = DailyLimitPolicy.rollover(stored, LocalDate.now().toEpochDay())
            if (today != stored) save(today)
            return today
        }

        private suspend fun save(next: DailyTally) {
            tally = next
            store.setTally(next)
        }

        private fun isOnScreen(): Boolean =
            ProcessLifecycleOwner
                .get()
                .lifecycle.currentState
                .isAtLeast(Lifecycle.State.STARTED)

        private fun now(): Long = SystemClock.elapsedRealtime()

        private companion object {
            /** Wakes just after midnight, so the new day has begun when the count rolls over. */
            const val MIDNIGHT_SLACK_MS = 1_000L
        }
    }
