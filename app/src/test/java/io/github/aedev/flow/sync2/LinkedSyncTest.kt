package io.github.aedev.flow.sync2

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.sync2.data.LinkedDatabase
import io.github.aedev.flow.sync2.device.DeviceKeys
import io.github.aedev.flow.sync2.service.LinkedReset
import io.github.aedev.flow.sync2.service.LinkedSync
import io.github.aedev.flow.sync2.service.SyncIndicator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A sync stopped because whoever ran it went away is not a failure to show the user. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class LinkedSyncTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun a_cancelled_sync_is_not_reported_as_failed() =
        runTest {
            val db = mockk<LinkedDatabase> { every { groups() } returns mockk { coEvery { group() } coAnswers { awaitCancellation() } } }
            val sync =
                LinkedSync(
                    context,
                    db,
                    DeviceKeys(context),
                    mockk(),
                    mockk(),
                    mockk(),
                    SyncIndicator(context),
                    mockk<LinkedReset> {
                        every {
                            leaveFailed
                        } returns MutableStateFlow(null)
                    },
                    mockk(),
                    mockk(),
                )
            val running = launch { sync.syncNow() }
            sync.syncing.first { it }
            running.cancelAndJoin()
            assertNull(sync.lastSync.value)
            assertFalse(sync.syncing.value)
        }
}
