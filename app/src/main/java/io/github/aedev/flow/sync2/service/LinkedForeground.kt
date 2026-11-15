package io.github.aedev.flow.sync2.service

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.di.ApplicationScope
import io.github.aedev.flow.sync2.data.LinkedDatabase
import io.github.aedev.flow.sync2.lan.LanSync
import io.github.aedev.flow.sync2.live.Handoff
import io.github.aedev.flow.sync2.live.NowPlaying
import io.github.aedev.flow.sync2.live.RemoteDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ties linked sync to the app being in use. Live messages, the live socket and LAN sessions run while
 * the app is in the foreground or playing, and stop otherwise to spare the battery. What changes here
 * goes out soon after while the app is in the foreground, and the rest as soon as it goes to the background.
 */
@Singleton
class LinkedForeground
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val db: LinkedDatabase,
        private val live: LinkedLive,
        private val lan: LanSync,
        private val now: NowPlaying,
        private val handoff: Handoff,
        private val remote: RemoteDriver,
        private val push: LinkedPush,
        @ApplicationScope private val scope: CoroutineScope,
    ) : DefaultLifecycleObserver {
        private val foreground = MutableStateFlow(false)

        fun install() {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
            remote.install()
            scope.launch {
                combine(foreground, now.playing) { shown, playing -> shown || playing }
                    .distinctUntilChanged()
                    .collectLatest { active -> if (active) runLive() }
            }
            scope.launch {
                combine(foreground, db.groups().observeGroup().map { it != null }) { shown, linked -> shown && linked }
                    .distinctUntilChanged()
                    .collectLatest { pushing -> if (pushing) push.run() }
            }
        }

        private suspend fun runLive() {
            live.start()
            lan.start()
            try {
                coroutineScope {
                    launch { handoff.run() }
                    launch { remote.run() }
                    now.run()
                }
            } finally {
                live.stop()
                lan.stop()
            }
        }

        override fun onStart(owner: LifecycleOwner) {
            foreground.value = true
        }

        override fun onStop(owner: LifecycleOwner) {
            foreground.value = false
            scope.launch { if (db.groups().group() != null) LinkedSyncWorker.syncSoon(context) }
        }
    }
