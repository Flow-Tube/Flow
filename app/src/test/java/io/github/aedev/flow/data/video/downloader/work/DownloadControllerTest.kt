package io.github.aedev.flow.data.video.downloader.work

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.dao.DownloadDao
import io.github.aedev.flow.data.local.entity.DownloadEntity
import io.github.aedev.flow.data.local.entity.DownloadFileType
import io.github.aedev.flow.data.local.entity.DownloadItemEntity
import io.github.aedev.flow.data.local.entity.DownloadItemStatus
import io.github.aedev.flow.data.local.entity.DownloadWithItems
import io.github.aedev.flow.data.video.VideoDownloadManager
import io.github.aedev.flow.data.video.downloader.request.DownloadRequest
import io.github.aedev.flow.data.video.downloader.tags.DownloadKind
import io.github.aedev.flow.data.video.downloader.tags.DownloadTags
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadControllerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = mockk(relaxed = true)
    private val dao: DownloadDao = mockk(relaxed = true)
    private val downloads: VideoDownloadManager = mockk(relaxed = true)
    private val preferences: PlayerPreferences = mockk(relaxed = true)
    private val workManager: WorkManager = mockk(relaxed = true)
    private lateinit var controller: DownloadController

    private val request =
        DownloadRequest(
            tags = DownloadTags(kind = DownloadKind.MUSIC, videoId = "song", title = "Intro", artists = listOf("A")),
            durationSeconds = 60,
        )

    @Before
    fun setUp() {
        mockkObject(WorkManager.Companion)
        every { WorkManager.getInstance(any()) } returns workManager
        every { downloads.stagingDirectory() } returns folder.root
        every { preferences.downloadOverWifiOnly } returns flowOf(false)
        controller = DownloadController(context, dao, downloads, preferences)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun existing(status: DownloadItemStatus) =
        DownloadWithItems(
            DownloadEntity(videoId = "song", title = "Intro", uploader = "A"),
            listOf(
                DownloadItemEntity(videoId = "song", fileType = DownloadFileType.AUDIO, fileName = "a", filePath = "/a", status = status),
            ),
        )

    @Test
    fun `a new request is written as a pending row with its request, then the queue runs`() =
        runTest {
            coEvery { dao.getDownloadWithItems("song") } returns null
            val row = slot<DownloadEntity>()
            val items = slot<List<DownloadItemEntity>>()
            coEvery { dao.replaceDownload(capture(row), capture(items)) } returns Unit

            assertThat(controller.enqueue(request)).isEqualTo(EnqueueOutcome.QUEUED)

            assertThat(row.captured.kind).isEqualTo(DownloadKind.MUSIC)
            assertThat(DownloadRequest.decode(row.captured.requestJson)).isEqualTo(request)
            assertThat(items.captured.single().status).isEqualTo(DownloadItemStatus.PENDING)
            assertThat(items.captured.single().filePath).startsWith(folder.root.path)
            verify(
                exactly = 1,
            ) { workManager.enqueueUniqueWork(DownloadQueueWorker.UNIQUE_NAME, ExistingWorkPolicy.KEEP, any<OneTimeWorkRequest>()) }
        }

    @Test
    fun `wifi only makes the queue wait for an unmetered network`() =
        runTest {
            every { preferences.downloadOverWifiOnly } returns flowOf(true)
            val work = slot<OneTimeWorkRequest>()
            every { workManager.enqueueUniqueWork(any(), any(), capture(work)) } returns mockk(relaxed = true)

            controller.kick()

            assertThat(work.captured.workSpec.constraints.requiredNetworkType).isEqualTo(NetworkType.UNMETERED)
        }

    @Test
    fun `a finished download is kept unless it is being replaced`() =
        runTest {
            coEvery { dao.getDownloadWithItems("song") } returns existing(DownloadItemStatus.COMPLETED)

            assertThat(controller.enqueue(request)).isEqualTo(EnqueueOutcome.ALREADY_DOWNLOADED)
            coVerify(exactly = 0) { dao.replaceDownload(any(), any()) }

            assertThat(controller.enqueue(request, replaceExisting = true)).isEqualTo(EnqueueOutcome.QUEUED)
            coVerify(exactly = 1) { downloads.deleteDownload("song") }
        }

    @Test
    fun `a download already running is not queued twice or replaced`() =
        runTest {
            coEvery { dao.getDownloadWithItems("song") } returns existing(DownloadItemStatus.DOWNLOADING)

            assertThat(controller.enqueue(request, replaceExisting = true)).isEqualTo(EnqueueOutcome.ALREADY_QUEUED)
            coVerify(exactly = 0) { dao.replaceDownload(any(), any()) }
        }

    private fun waiting(stored: DownloadRequest) =
        DownloadWithItems(
            DownloadEntity(videoId = "song", title = "Intro", uploader = "A", requestJson = stored.encode()),
            listOf(
                DownloadItemEntity(
                    videoId = "song",
                    fileType = DownloadFileType.AUDIO,
                    fileName = "a",
                    filePath = "/a",
                    status = DownloadItemStatus.PENDING,
                ),
            ),
        )

    @Test
    fun `a waiting download gives way to a different pick from a dialog`() =
        runTest {
            coEvery { dao.getDownloadWithItems("song") } returns waiting(request.copy(audioTrackId = "en.4"))
            coEvery { dao.replaceDownload(any(), any()) } returns Unit

            assertThat(controller.enqueue(request.copy(audioTrackId = "ar.3"), replaceExisting = true)).isEqualTo(EnqueueOutcome.QUEUED)
            coVerify(exactly = 1) { dao.replaceDownload(any(), any()) }
        }

    @Test
    fun `a waiting download stays for the same pick, and for a request that is not replacing it`() =
        runTest {
            coEvery { dao.getDownloadWithItems("song") } returns waiting(request)

            assertThat(controller.enqueue(request, replaceExisting = true)).isEqualTo(EnqueueOutcome.ALREADY_QUEUED)
            assertThat(controller.enqueue(request.copy(audioTrackId = "ar.3"))).isEqualTo(EnqueueOutcome.ALREADY_QUEUED)
            coVerify(exactly = 0) { dao.replaceDownload(any(), any()) }
        }

    @Test
    fun `a failed or paused download is queued again over its old row`() =
        runTest {
            coEvery { dao.getDownloadWithItems("song") } returns existing(DownloadItemStatus.FAILED)

            assertThat(controller.enqueue(request)).isEqualTo(EnqueueOutcome.QUEUED)
            coVerify(exactly = 1) { dao.replaceDownload(any(), any()) }
        }
}
