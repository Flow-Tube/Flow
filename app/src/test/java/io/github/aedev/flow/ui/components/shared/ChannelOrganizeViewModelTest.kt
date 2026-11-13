package io.github.aedev.flow.ui.components.shared

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.dao.SubscriptionGroupDao
import io.github.aedev.flow.data.local.entity.SubscriptionGroupEntity
import io.github.aedev.flow.data.notes.Note
import io.github.aedev.flow.data.notes.NoteKind
import io.github.aedev.flow.data.notes.NoteSubject
import io.github.aedev.flow.data.notes.NotesRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelOrganizeViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val groupDao: SubscriptionGroupDao = mockk(relaxed = true)
    private val notesRepository: NotesRepository = mockk(relaxed = true)
    private val playerPreferences: PlayerPreferences = mockk(relaxed = true)
    private val target = ChannelOrganizeTarget(channelId = "UC2", title = "Alan Becker", avatarUrl = "https://a", handle = "@alanbecker")

    private lateinit var viewModel: ChannelOrganizeViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { groupDao.getAllGroups() } returns flowOf(emptyList())
        every { playerPreferences.effectiveChannelNotesEnabled } returns flowOf(true)
        viewModel = ChannelOrganizeViewModel(groupDao, notesRepository, playerPreferences)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `manageGroups opens the group sheet for the channel`() {
        viewModel.manageGroups(target)

        assertThat(viewModel.request.value).isEqualTo(ChannelOrganizeRequest(target, ChannelOrganizeKind.Groups))
    }

    @Test
    fun `a channel without an id opens nothing`() {
        viewModel.manageGroups(ChannelOrganizeTarget(channelId = ""))
        viewModel.editNote(ChannelOrganizeTarget(channelId = ""))

        assertThat(viewModel.request.value).isNull()
    }

    @Test
    fun `startNewGroup keeps the channel and dismiss clears it`() {
        viewModel.manageGroups(target)
        viewModel.startNewGroup()
        assertThat(viewModel.request.value?.kind).isEqualTo(ChannelOrganizeKind.NewGroup)
        assertThat(viewModel.request.value?.target).isEqualTo(target)

        viewModel.dismiss()
        assertThat(viewModel.request.value).isNull()
    }

    @Test
    fun `editNote opens the editor with the stored note`() =
        runTest {
            coEvery { notesRepository.get(NoteKind.Channel, "UC2") } returns
                Note("UC2", NoteKind.Channel, "Great animator", 1L, null, null)

            viewModel.editNote(target)

            val request = withTimeout(WRITE_TIMEOUT_MS) { viewModel.request.filterNotNull().first() }
            assertThat(request.kind).isEqualTo(ChannelOrganizeKind.Note)
            assertThat(request.noteText).isEqualTo("Great animator")
        }

    @Test
    fun `saveNote stores the text with what the menu knew about the channel`() {
        viewModel.manageGroups(target)

        viewModel.saveNote("Why I subscribed")

        coVerify(timeout = WRITE_TIMEOUT_MS) {
            notesRepository.save(
                NoteKind.Channel,
                "UC2",
                "Why I subscribed",
                NoteSubject(
                    title = "Alan Becker",
                    channelId = "UC2",
                    thumbnailUrl = "https://a",
                    channelAvatarUrl = "https://a",
                    channelHandle = "@alanbecker",
                ),
            )
        }
    }

    @Test
    fun `setInGroup adds the channel to the stored member list`() {
        coEvery { groupDao.getAllGroupsOnce() } returns listOf(SubscriptionGroupEntity(name = "Music", channelIds = "UC1", sortOrder = 0))
        viewModel.manageGroups(target)

        viewModel.setInGroup("Music", inGroup = true)

        coVerify(timeout = WRITE_TIMEOUT_MS) {
            groupDao.updateGroup(SubscriptionGroupEntity(name = "Music", channelIds = "UC1,UC2", sortOrder = 0))
        }
    }

    @Test
    fun `setInGroup removes the channel when it is already a member`() {
        coEvery { groupDao.getAllGroupsOnce() } returns
            listOf(SubscriptionGroupEntity(name = "Music", channelIds = "UC1,UC2", sortOrder = 0))
        viewModel.manageGroups(target)

        viewModel.setInGroup("Music", inGroup = false)

        coVerify(timeout = WRITE_TIMEOUT_MS) {
            groupDao.updateGroup(SubscriptionGroupEntity(name = "Music", channelIds = "UC1", sortOrder = 0))
        }
    }

    @Test
    fun `setInGroup ignores a group that no longer exists`() {
        coEvery { groupDao.getAllGroupsOnce() } returns emptyList()
        viewModel.manageGroups(target)

        viewModel.setInGroup("Gone", inGroup = true)

        coVerify(timeout = WRITE_TIMEOUT_MS) { groupDao.getAllGroupsOnce() }
        coVerify(exactly = 0) { groupDao.updateGroup(any()) }
    }

    @Test
    fun `createGroup refuses a name that already exists`() {
        coEvery { groupDao.exists("Music") } returns true
        viewModel.manageGroups(target)

        viewModel.createGroup("Music")

        coVerify(timeout = WRITE_TIMEOUT_MS) { groupDao.exists("Music") }
        coVerify(exactly = 0) { groupDao.insertGroup(any()) }
    }

    @Test
    fun `createGroup stores the trimmed name with the channel as its first member`() {
        coEvery { groupDao.exists(any()) } returns false
        coEvery { groupDao.getAllGroupsOnce() } returns emptyList()
        viewModel.manageGroups(target)

        viewModel.createGroup("  Podcasts  ")

        coVerify(timeout = WRITE_TIMEOUT_MS) {
            groupDao.insertGroup(SubscriptionGroupEntity(name = "Podcasts", channelIds = "UC2", sortOrder = 0))
        }
    }

    private companion object {
        /** Writes run on PerformanceDispatcher.diskIO, which the test scheduler cannot advance. */
        const val WRITE_TIMEOUT_MS = 2_000L
    }
}
