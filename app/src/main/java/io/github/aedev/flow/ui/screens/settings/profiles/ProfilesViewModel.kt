package io.github.aedev.flow.ui.screens.settings.profiles

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.profile.ActiveProfile
import io.github.aedev.flow.data.profile.CreateResult
import io.github.aedev.flow.data.profile.Profile
import io.github.aedev.flow.data.profile.ProfileAvatar
import io.github.aedev.flow.data.profile.ProfileRegistryState
import io.github.aedev.flow.data.profile.ProfileRepository
import io.github.aedev.flow.data.profile.ProfileSwitcher
import io.github.aedev.flow.data.profile.SwitchRefusal
import io.github.aedev.flow.data.profile.SwitchResult
import io.github.aedev.flow.sync2.service.LinkedProfiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

internal sealed interface ProfilesMessage {
    data object Deleted : ProfilesMessage

    data object LimitReached : ProfilesMessage

    data object CopyFailed : ProfilesMessage

    data object PhotoFailed : ProfilesMessage

    data class SwitchRefused(
        val reason: SwitchRefusal?,
    ) : ProfilesMessage
}

/** The Profiles page and the profile editor. Switching restarts the process, so a switch that works never returns. */
@HiltViewModel
internal class ProfilesViewModel
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val profiles: ProfileRepository,
        private val switcher: ProfileSwitcher,
        linked: LinkedProfiles,
    ) : ViewModel() {
        val state: StateFlow<ProfileRegistryState?> = profiles.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

        /** The profiles that sync with the other linked devices. */
        val synced: StateFlow<Set<String>> =
            linked.choices
                .map { choices -> choices.local.filter { it.link != null }.mapTo(HashSet()) { it.id } }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
        val runningId: String = ActiveProfile.get(context).id

        private val _message = MutableStateFlow<ProfilesMessage?>(null)
        val message: StateFlow<ProfilesMessage?> = _message.asStateFlow()

        private val _busy = MutableStateFlow(false)
        val busy: StateFlow<Boolean> = _busy.asStateFlow()

        fun consumeMessage() {
            _message.value = null
        }

        fun switchTo(id: String) = work { switch(id) }

        fun setAskWhosWatching(ask: Boolean) {
            viewModelScope.launch { profiles.setAskWhosWatching(ask) }
        }

        fun rename(
            id: String,
            name: String,
        ) = work { profiles.rename(id, name) }

        fun delete(id: String) =
            work {
                if (profiles.delete(id)) _message.value = ProfilesMessage.Deleted
            }

        /** Creates [draft], then switches to it when [andSwitch]; [onCreated] runs once it exists and the switch didn't happen. */
        fun create(
            draft: ProfileDraft,
            andSwitch: Boolean,
            onCreated: () -> Unit,
        ) = work {
            val avatar = draft.avatar.takeUnless { it.kind == ProfileAvatar.PHOTO } ?: ProfileAvatar()
            when (val result = profiles.create(draft.name, draft.colorKey, avatar, draft.copy)) {
                CreateResult.LimitReached -> {
                    _message.value = ProfilesMessage.LimitReached
                }

                is CreateResult.CopyFailed -> {
                    _message.value = ProfilesMessage.CopyFailed
                }

                is CreateResult.Created -> {
                    draft.photo?.let { usePhoto(result.profile.id, it) }
                    if (andSwitch) switch(result.profile.id)
                    onCreated()
                }
            }
        }

        fun save(
            profile: Profile,
            draft: ProfileDraft,
            onSaved: () -> Unit,
        ) = work {
            if (draft.name.trim() != profile.name) profiles.rename(profile.id, draft.name)
            val photo = draft.photo
            when {
                photo != null -> {
                    profiles.setAppearance(profile.id, draft.colorKey, profile.avatar)
                    usePhoto(profile.id, photo)
                }

                profile.avatar.kind == ProfileAvatar.PHOTO && draft.avatar.kind != ProfileAvatar.PHOTO -> {
                    profiles.removePhoto(profile.id)
                    profiles.setAppearance(profile.id, draft.colorKey, draft.avatar)
                }

                else -> {
                    profiles.setAppearance(profile.id, draft.colorKey, draft.avatar)
                }
            }
            onSaved()
        }

        suspend fun profile(id: String): Profile? =
            profiles.state
                .first()
                .profiles
                .firstOrNull { it.id == id }

        private suspend fun usePhoto(
            id: String,
            photo: Uri,
        ) {
            if (!profiles.setPhoto(id, photo)) _message.value = ProfilesMessage.PhotoFailed
        }

        private suspend fun switch(id: String) {
            val result =
                try {
                    switcher.switchTo(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            if (result !is SwitchResult.Restarting) {
                _message.value = ProfilesMessage.SwitchRefused((result as? SwitchResult.Refused)?.reason)
            }
        }

        private fun work(block: suspend () -> Unit) {
            if (_busy.value) return
            _busy.value = true
            viewModelScope.launch {
                try {
                    block()
                } finally {
                    _busy.value = false
                }
            }
        }
    }
