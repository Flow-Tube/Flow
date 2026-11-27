package io.github.aedev.flow.ui.screens.settings.scrobbling

import android.content.Context
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.R
import io.github.aedev.flow.data.scrobble.AudioscrobblerKeys
import io.github.aedev.flow.data.scrobble.ScrobbleService
import io.github.aedev.flow.data.scrobble.ScrobbleSettings
import io.github.aedev.flow.data.scrobble.ScrobbleStore
import io.github.aedev.flow.data.scrobble.ScrobbleWebhook
import io.github.aedev.flow.data.scrobble.ScrobbleWorker
import io.github.aedev.flow.data.scrobble.Scrobbler
import io.github.aedev.flow.data.scrobble.TasteImport
import io.github.aedev.flow.ui.screens.settings.SettingsViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

internal sealed interface SignInState {
    data object Idle : SignInState

    data object Working : SignInState

    data object Done : SignInState

    data class Failed(
        val message: String?,
    ) : SignInState
}

@HiltViewModel
internal class ScrobblingViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val store: ScrobbleStore,
        private val scrobbler: Scrobbler,
        private val tasteImport: TasteImport,
    ) : SettingsViewModel() {
        val settings = store.settings.asState(ScrobbleSettings())

        /** Last.fm sign-in needs a key: the build's, or the viewer's own once they turn it on. */
        val lastFmReady = store.settings.map { scrobbler.lastFmKeys(it) != null }.asState(false)

        private val _signIn = MutableStateFlow<SignInState>(SignInState.Idle)
        val signIn: StateFlow<SignInState> = _signIn.asStateFlow()

        fun signIn(
            service: ScrobbleService,
            userName: String,
            passwordOrToken: String,
        ) {
            _signIn.value = SignInState.Working
            viewModelScope.launch {
                _signIn.value =
                    scrobbler
                        .signIn(service, userName, passwordOrToken)
                        .fold(onSuccess = { SignInState.Done }, onFailure = { SignInState.Failed(it.message) })
            }
        }

        private val _importing = MutableStateFlow<ScrobbleService?>(null)
        val importing: StateFlow<ScrobbleService?> = _importing.asStateFlow()

        private val _messages = Channel<String>(Channel.BUFFERED)

        /** Import results, for the page's snackbar. */
        val messages: Flow<String> = _messages.receiveAsFlow()

        fun importTaste(service: ScrobbleService) {
            if (_importing.value != null) return
            _importing.value = service
            viewModelScope.launch {
                val message =
                    tasteImport.importTopArtists(service).fold(
                        onSuccess = { added ->
                            if (added == 0) {
                                context.getString(R.string.scrobbling_import_none)
                            } else {
                                context.resources.getQuantityString(R.plurals.scrobbling_import_done, added, added)
                            }
                        },
                        onFailure = { context.getString(R.string.scrobbling_import_failed) },
                    )
                _importing.value = null
                _messages.send(message)
            }
        }

        fun resetSignIn() {
            _signIn.value = SignInState.Idle
        }

        fun signOut(service: ScrobbleService) = write { scrobbler.signOut(service) }

        /** A Last.fm session belongs to the key it was made with, so changing the key signs out. */
        fun setOwnKeys(
            enabled: Boolean,
            keys: AudioscrobblerKeys,
        ) = write {
            val before = store.current()
            store.setOwnKeys(enabled, keys)
            if (before.ownKeyEnabled != enabled || (enabled && before.ownKeys != keys)) scrobbler.signOut(ScrobbleService.LASTFM)
        }

        fun setNowPlaying(enabled: Boolean) = write { store.setNowPlaying(enabled) }

        fun setScrobbleLocal(enabled: Boolean) = write { store.setScrobbleLocal(enabled) }

        fun setSendLikes(enabled: Boolean) = write { store.setSendLikes(enabled) }

        fun sendNow() = ScrobbleWorker.enqueue(context)

        fun saveWebhook(
            id: String?,
            name: String,
            url: String,
        ) = write { store.saveWebhook(ScrobbleWebhook(id ?: UUID.randomUUID().toString(), name.trim(), url.trim())) }

        fun removeWebhook(id: String) = write { store.removeWebhook(id) }
    }
