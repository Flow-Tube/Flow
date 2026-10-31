package io.github.aedev.flow.ui.screens.sync.linked

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.sync2.device.WebDavLogin
import io.github.aedev.flow.sync2.live.LiveConsent
import io.github.aedev.flow.sync2.live.LiveDevices
import io.github.aedev.flow.sync2.live.deviceKind
import io.github.aedev.flow.sync2.service.LastSync
import io.github.aedev.flow.sync2.service.LinkPhase
import io.github.aedev.flow.sync2.service.LinkedActions
import io.github.aedev.flow.sync2.service.LinkedDevice
import io.github.aedev.flow.sync2.service.LinkedError
import io.github.aedev.flow.sync2.service.linkedError
import io.github.aedev.flow.sync2.store.JoinRequest
import io.github.aedev.flow.sync2.store.StoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The group as this device sees it; null fields are not known yet. */
internal data class LinkedUiState(
    val storeUrl: String? = null,
    val recoverySaved: Boolean = true,
    val lastSync: LastSync? = null,
    val devices: List<LinkedDevice>? = null,
    /** Devices asking to join with the admin token. */
    val requests: List<JoinRequest> = emptyList(),
    /** Devices met on this network while the app runs. */
    val nearby: Map<String, Long> = emptyMap(),
    /** What each device told it is ("phone", "tv"…), this one included, for its icon. */
    val kinds: Map<String, String> = emptyMap(),
    /** Recovery words to show once, until the user proves they wrote them down. */
    val words: String? = null,
    val busy: Boolean = false,
    val syncing: Boolean = false,
    val error: LinkedError? = null,
) {
    val linked: Boolean get() = storeUrl != null
}

/** What the screen itself holds: the words being shown and the outcome of the last action. */
private data class Local(
    val devices: List<LinkedDevice>? = null,
    val requests: List<JoinRequest> = emptyList(),
    /** Devices met on this network while the app runs. */
    val nearby: Set<String> = emptySet(),
    val words: String? = null,
    val busy: Boolean = false,
    val error: LinkedError? = null,
)

@HiltViewModel
internal class LinkedDevicesViewModel
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val actions: LinkedActions,
        private val liveConsent: LiveConsent,
        live: LiveDevices,
    ) : ViewModel() {
        private val ownKind = deviceKind(context)

        /** Which devices may play here and control playback (spec/live.md, Consent). */
        val consent: StateFlow<Map<String, Boolean>> =
            liveConsent.remembered.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

        fun setConsent(
            id: String,
            allowed: Boolean?,
        ) {
            viewModelScope.launch { if (allowed == null) liveConsent.forget(id) else liveConsent.remember(id, allowed) }
        }

        private val local = MutableStateFlow(Local())

        val state: StateFlow<LinkedUiState> =
            combine(
                actions.group,
                actions.lastSync,
                actions.nearby,
                actions.syncing,
                combine(local, live.devices) { l, peers -> l to peers },
            ) { group, lastSync, nearby, syncing, (l, peers) ->
                val kinds = peers.mapNotNull { (did, peer) -> peer.kind?.let { did to it } }.toMap()
                val own = l.devices?.firstOrNull { it.thisDevice }?.id
                LinkedUiState(
                    group?.storeUrl,
                    group?.recoverySaved ?: true,
                    lastSync,
                    l.devices,
                    l.requests,
                    nearby,
                    if (own != null) kinds + (own to ownKind) else kinds,
                    l.words,
                    l.busy,
                    syncing,
                    l.error ?: lastSync?.error,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LinkedUiState())

        val link: StateFlow<LinkPhase> = actions.link

        /** Runs one user action with the shared busy flag, showing its failure as the error. */
        private fun run(action: suspend () -> Unit) {
            viewModelScope.launch {
                local.update { it.copy(busy = true, error = null) }
                val error =
                    try {
                        action()
                        null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        e.linkedError()
                    }
                local.update { it.copy(busy = false, error = error) }
            }
        }

        fun refreshDevices() = run { local.update { it.copy(devices = actions.devices()) } }

        fun create(
            url: String,
            adminToken: String,
        ) = run { local.update { it.copy(words = actions.create(url, adminToken)) } }

        fun createOnWebDav(
            url: String,
            user: String,
            password: String,
        ) = run { local.update { it.copy(words = actions.createOnWebDav(url, user, password)) } }

        fun restoreFromWebDav(
            url: String,
            user: String,
            password: String,
            words: String,
        ) = run { local.update { it.copy(words = actions.restoreFromWebDav(url, user, password, words)) } }

        fun updateLogin(
            user: String,
            password: String,
        ) = run { actions.updateLogin(user, password) }

        fun resetAndCreate(
            url: String,
            adminToken: String,
        ) = run { local.update { it.copy(words = actions.resetAndCreate(url, adminToken)) } }

        fun createOnNetwork() = run { local.update { it.copy(words = actions.createOnNetwork()) } }

        fun restore(
            url: String,
            words: String,
        ) = run { local.update { it.copy(words = actions.restore(url, words)) } }

        fun confirmWordsSaved() =
            run {
                actions.confirmRecoverySaved()
                local.update { it.copy(words = null) }
            }

        fun rename(
            id: String,
            name: String,
        ) = run {
            actions.rename(id, name)
            local.update { it.copy(devices = actions.devices()) }
        }

        fun remove(id: String) =
            run {
                actions.remove(id)
                local.update { it.copy(devices = actions.devices()) }
            }

        fun leave() =
            run {
                actions.leave()
                local.value = Local()
            }

        val leaveFailed: StateFlow<LinkedError?> = actions.leaveFailed

        fun keepGroup() = actions.keepGroup()

        fun forget() =
            run {
                actions.forget()
                local.value = Local()
            }

        fun syncNow() = actions.syncNow()

        fun enterCode(
            code: String,
            login: WebDavLogin? = null,
        ) = actions.enterCode(code, login)

        fun joinWithAdmin(
            url: String,
            adminToken: String,
        ) = actions.joinWithAdmin(url, adminToken)

        /** Polled while the screen is open; a failure here only means no requests are shown. */
        suspend fun refreshRequests() {
            val requests =
                try {
                    actions.joinRequests()
                } catch (_: StoreException) {
                    emptyList()
                }
            local.update { it.copy(requests = requests) }
        }

        fun accept(id: String) {
            local.update { l -> l.copy(requests = l.requests.filter { it.id != id }) }
            actions.accept(id)
        }

        fun decline(id: String) =
            run {
                local.update { l -> l.copy(requests = l.requests.filter { it.id != id }) }
                actions.decline(id)
            }

        fun showCode() = actions.showCode()

        fun addDevice() = actions.addDevice()

        fun answer(accept: Boolean) = actions.answer(accept)

        fun closeLink() {
            actions.cancel()
            if (state.value.linked) refreshDevices()
        }
    }
