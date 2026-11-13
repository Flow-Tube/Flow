package io.github.aedev.flow.ui.components.shared

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.dao.SubscriptionGroupDao
import io.github.aedev.flow.data.local.entity.SubscriptionGroupEntity
import io.github.aedev.flow.data.model.Channel
import io.github.aedev.flow.data.model.SubscriptionGroup
import io.github.aedev.flow.data.model.toUiModel
import io.github.aedev.flow.data.model.withMember
import io.github.aedev.flow.data.notes.NoteKind
import io.github.aedev.flow.data.notes.NoteSubject
import io.github.aedev.flow.data.notes.NotesRepository
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val ORGANIZE_SUBSCRIPTION_TIMEOUT_MS = 5_000L

/** The channel a note or group action is about, with what its note remembers of it. */
@Immutable
data class ChannelOrganizeTarget(
    val channelId: String,
    val title: String = "",
    val avatarUrl: String = "",
    val handle: String = "",
    val subscriberCountText: String = "",
)

fun Channel.toOrganizeTarget(): ChannelOrganizeTarget =
    ChannelOrganizeTarget(channelId = id, title = name, avatarUrl = thumbnailUrl, handle = handle)

internal enum class ChannelOrganizeKind { Note, Groups, NewGroup }

@Immutable
internal data class ChannelOrganizeRequest(
    val target: ChannelOrganizeTarget,
    val kind: ChannelOrganizeKind,
    val noteText: String = "",
)

/** Notes and subscription groups for any channel menu, drawn once by [ChannelOrganizeHost]. */
@HiltViewModel
class ChannelOrganizeViewModel
    @Inject
    constructor(
        private val subscriptionGroupDao: SubscriptionGroupDao,
        private val notesRepository: NotesRepository,
        playerPreferences: PlayerPreferences,
    ) : ViewModel() {
        private val pending = MutableStateFlow<ChannelOrganizeRequest?>(null)
        internal val request: StateFlow<ChannelOrganizeRequest?> = pending.asStateFlow()

        val notesEnabled: StateFlow<Boolean> =
            playerPreferences.effectiveChannelNotesEnabled
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(ORGANIZE_SUBSCRIPTION_TIMEOUT_MS), false)

        val groups: StateFlow<List<SubscriptionGroup>> =
            subscriptionGroupDao
                .getAllGroups()
                .map { entities -> entities.map { it.toUiModel() } }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(ORGANIZE_SUBSCRIPTION_TIMEOUT_MS), emptyList())

        fun editNote(target: ChannelOrganizeTarget) {
            if (target.channelId.isBlank()) return
            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                val text = notesRepository.get(NoteKind.Channel, target.channelId)?.text.orEmpty()
                pending.value = ChannelOrganizeRequest(target, ChannelOrganizeKind.Note, text)
            }
        }

        fun manageGroups(target: ChannelOrganizeTarget) {
            if (target.channelId.isBlank()) return
            pending.value = ChannelOrganizeRequest(target, ChannelOrganizeKind.Groups)
        }

        internal fun startNewGroup() {
            pending.value = pending.value?.copy(kind = ChannelOrganizeKind.NewGroup)
        }

        internal fun dismiss() {
            pending.value = null
        }

        internal fun saveNote(text: String) {
            val target = pending.value?.target ?: return
            val subject =
                target.takeIf { it.title.isNotBlank() }?.let {
                    NoteSubject(
                        title = it.title,
                        channelId = it.channelId,
                        thumbnailUrl = it.avatarUrl,
                        channelAvatarUrl = it.avatarUrl,
                        channelHandle = it.handle,
                        subscriberCountText = it.subscriberCountText,
                    )
                }
            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                notesRepository.save(NoteKind.Channel, target.channelId, text, subject)
            }
        }

        internal fun setInGroup(
            groupName: String,
            inGroup: Boolean,
        ) {
            val channelId = pending.value?.target?.channelId ?: return
            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                val group = subscriptionGroupDao.getAllGroupsOnce().firstOrNull { it.name == groupName } ?: return@launch
                subscriptionGroupDao.updateGroup(group.withMember(channelId, inGroup))
            }
        }

        internal fun createGroup(groupName: String) {
            val channelId = pending.value?.target?.channelId ?: return
            viewModelScope.launch(PerformanceDispatcher.diskIO) {
                val name = groupName.trim()
                if (name.isEmpty() || subscriptionGroupDao.exists(name)) return@launch
                val order = subscriptionGroupDao.getAllGroupsOnce().size
                subscriptionGroupDao.insertGroup(
                    SubscriptionGroupEntity(name = name, channelIds = channelId, sortOrder = order),
                )
            }
        }
    }

/** The activity's [ChannelOrganizeViewModel], so every menu and the one host share it. */
@Composable
fun sharedChannelOrganizer(): ChannelOrganizeViewModel {
    val activity = LocalContext.current as? ComponentActivity
    return if (activity != null) hiltViewModel(activity) else hiltViewModel()
}
