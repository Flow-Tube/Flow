package io.github.aedev.flow.data.scrobble

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.BuildConfig
import io.github.aedev.flow.data.local.PrivacyGate
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.network.ProxyAwareClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends what the music player plays to every signed-in service. Finished listens are queued on the
 * device first and sent by [ScrobbleWorker], so listening offline loses nothing. During Deep Flow
 * nothing is sent unless the user allowed it.
 */
@Singleton
class Scrobbler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val store: ScrobbleStore,
        private val privacyGate: PrivacyGate,
    ) {
        private val work = SupervisorJob()
        private val scope = CoroutineScope(work + Dispatchers.IO)
        private val http = ProxyAwareClient()

        /** Waits for listens and loves still being queued, before a profile switch restarts the process. */
        suspend fun awaitWrites() = work.children.toList().joinAll()

        private val lastFm = AudioscrobblerClient(AudioscrobblerClient.LASTFM_URL, http::get)
        private val libreFm = AudioscrobblerClient(AudioscrobblerClient.LIBREFM_URL, http::get, MobileSessionAuth.AUTH_TOKEN)
        private val listenBrainz = ListenBrainzClient(http::get)

        /** The key Last.fm calls are signed with: the viewer's own when they turned it on, else the build's. */
        fun lastFmKeys(settings: ScrobbleSettings): AudioscrobblerKeys? =
            if (settings.ownKeyEnabled) {
                settings.ownKeys.takeIf { it.isUsable }
            } else {
                BundledLastFmKeys.takeIf { it.isUsable }
            }

        fun onNowPlaying(
            track: MusicTrack,
            durationMs: Long,
        ) {
            scope.launch {
                val settings = store.current()
                if (!settings.nowPlaying || !settings.accepts(track) || !privacyGate.allowsScrobbling()) return@launch
                val entry = ScrobbleRules.entryFor(track, durationMs, System.currentTimeMillis()) ?: return@launch
                settings.accounts.forEach { (service, account) ->
                    val outcome =
                        when (service) {
                            ScrobbleService.LASTFM -> lastFmKeys(settings)?.let { lastFm.nowPlaying(entry, account, it) }
                            ScrobbleService.LIBREFM -> libreFm.nowPlaying(entry, account, AudioscrobblerClient.LibreFmKeys)
                            ScrobbleService.LISTENBRAINZ -> listenBrainz.nowPlaying(entry, account)
                        }
                    if (outcome == SendOutcome.SignedOut) store.setAccount(service, null)
                }
            }
        }

        fun onListened(
            track: MusicTrack,
            durationMs: Long,
            playedMs: Long,
            startedAtMs: Long,
        ) {
            if (!ScrobbleRules.counts(durationMs, playedMs)) return
            scope.launch {
                val settings = store.current()
                if (settings.accounts.isEmpty() || !settings.accepts(track) || !privacyGate.allowsScrobbling()) return@launch
                val entry = ScrobbleRules.entryFor(track, durationMs, startedAtMs) ?: return@launch
                settings.accounts.keys.forEach { store.enqueue(it, entry) }
                ScrobbleWorker.enqueue(context)
            }
        }

        /** Mirrors a like or unlike as a love on the services that have one; ListenBrainz needs MusicBrainz ids instead. */
        fun onLikeChanged(
            track: MusicTrack,
            liked: Boolean,
        ) {
            scope.launch {
                val settings = store.current()
                if (!settings.sendLikes || !settings.accepts(track)) return@launch
                val love = ScrobbleRules.loveFor(track, liked) ?: return@launch
                val services = settings.accounts.keys.filter { it != ScrobbleService.LISTENBRAINZ }
                if (services.isEmpty()) return@launch
                services.forEach { store.enqueueLove(it, love) }
                ScrobbleWorker.enqueue(context)
            }
        }

        /** Sends every queued listen and love; false when something is left to retry. */
        suspend fun flush(): Boolean {
            val settings = store.current()
            var done = true
            settings.accounts.forEach { (service, account) ->
                if (!flushLoves(service, account, settings)) done = false
                while (true) {
                    val batch = store.pending(service).take(batchSize(service))
                    if (batch.isEmpty()) break
                    when (send(service, batch, account, settings)) {
                        SendOutcome.Sent -> {
                            store.drop(service, batch)
                        }

                        SendOutcome.Retry -> {
                            done = false
                            break
                        }

                        SendOutcome.SignedOut -> {
                            Log.w(TAG, "$service signed the account out")
                            store.setAccount(service, null)
                            break
                        }
                    }
                }
            }
            return done
        }

        private suspend fun flushLoves(
            service: ScrobbleService,
            account: ScrobbleAccount,
            settings: ScrobbleSettings,
        ): Boolean {
            if (service == ScrobbleService.LISTENBRAINZ) return true
            val client = if (service == ScrobbleService.LIBREFM) libreFm else lastFm
            val keys = if (service == ScrobbleService.LIBREFM) AudioscrobblerClient.LibreFmKeys else lastFmKeys(settings)
            for (love in store.pendingLoves(service)) {
                when (keys?.let { client.setLoved(love, account, it) } ?: SendOutcome.SignedOut) {
                    SendOutcome.Sent -> store.dropLove(service, love)
                    SendOutcome.Retry -> return false
                    SendOutcome.SignedOut -> return true
                }
            }
            return true
        }

        suspend fun signIn(
            service: ScrobbleService,
            userName: String,
            passwordOrToken: String,
        ): Result<ScrobbleAccount> {
            val result =
                when (service) {
                    ScrobbleService.LASTFM -> {
                        val keys = lastFmKeys(store.current()) ?: return Result.failure(IllegalStateException("No API key"))
                        lastFm.signIn(userName.trim(), passwordOrToken, keys)
                    }

                    ScrobbleService.LIBREFM -> {
                        libreFm.signIn(userName.trim(), passwordOrToken, AudioscrobblerClient.LibreFmKeys)
                    }

                    ScrobbleService.LISTENBRAINZ -> {
                        listenBrainz.signIn(passwordOrToken.trim())
                    }
                }
            result.onSuccess { store.setAccount(service, it) }
            return result
        }

        suspend fun signOut(service: ScrobbleService) = store.setAccount(service, null)

        /** Last.fm only: Libre.fm and ListenBrainz have no similar-track data to read. */
        suspend fun similarTracks(
            artist: String,
            title: String,
            limit: Int,
        ): Result<List<Pair<String, String>>> {
            val settings = store.current()
            if (ScrobbleService.LASTFM !in settings.accounts) return Result.failure(IllegalStateException("Not signed in"))
            val keys = lastFmKeys(settings) ?: return Result.failure(IllegalStateException("No API key"))
            return lastFm.similarTracks(artist, title, keys, limit)
        }

        suspend fun topArtists(
            service: ScrobbleService,
            limit: Int,
        ): Result<List<String>> {
            val settings = store.current()
            val account = settings.accounts[service] ?: return Result.failure(IllegalStateException("Not signed in"))
            return when (service) {
                ScrobbleService.LASTFM -> {
                    val keys = lastFmKeys(settings) ?: return Result.failure(IllegalStateException("No API key"))
                    lastFm.topArtists(account.userName, keys, limit)
                }

                ScrobbleService.LIBREFM -> {
                    libreFm.topArtists(account.userName, AudioscrobblerClient.LibreFmKeys, limit)
                }

                ScrobbleService.LISTENBRAINZ -> {
                    listenBrainz.topArtists(account, limit)
                }
            }
        }

        private suspend fun send(
            service: ScrobbleService,
            batch: List<ScrobbleEntry>,
            account: ScrobbleAccount,
            settings: ScrobbleSettings,
        ): SendOutcome =
            when (service) {
                ScrobbleService.LASTFM -> lastFmKeys(settings)?.let { lastFm.scrobble(batch, account, it) } ?: SendOutcome.SignedOut
                ScrobbleService.LIBREFM -> libreFm.scrobble(batch, account, AudioscrobblerClient.LibreFmKeys)
                ScrobbleService.LISTENBRAINZ -> listenBrainz.scrobble(batch, account)
            }

        private fun batchSize(service: ScrobbleService) =
            if (service == ScrobbleService.LISTENBRAINZ) ListenBrainzClient.MAX_BATCH else AudioscrobblerClient.MAX_BATCH

        private fun ScrobbleSettings.accepts(track: MusicTrack) = scrobbleLocal || !LocalMediaIds.isLocal(track.videoId)

        private companion object {
            const val TAG = "Scrobbler"
            val BundledLastFmKeys = AudioscrobblerKeys(BuildConfig.LASTFM_API_KEY, BuildConfig.LASTFM_API_SECRET)
        }
    }
