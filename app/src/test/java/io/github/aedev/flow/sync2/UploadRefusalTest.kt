package io.github.aedev.flow.sync2

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.sync2.store.ChangeList
import io.github.aedev.flow.sync2.store.GroupStore
import io.github.aedev.flow.sync2.store.Mailbox
import io.github.aedev.flow.sync2.store.Remote
import io.github.aedev.flow.sync2.store.StoreException
import io.github.aedev.flow.sync2.store.StorePaths
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A store that won't take an object must not end a sync, and the device's log keeps its order. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class UploadRefusalTest {
    private val me = "ab".repeat(16)

    private fun own(seq: Long) = StorePaths.log(me, seq)

    /** Answers with [refusal] for the object at [refused]. */
    private class Picky(
        private val real: MemoryStore,
        var refused: String?,
        private val refusal: StoreException = StoreException.Refused(403, "forbidden"),
    ) : Remote,
        ChangeList by real,
        Mailbox by real {
        val tried = ArrayList<String>()

        override suspend fun putObject(
            path: String,
            bytes: ByteArray,
        ) {
            tried += path
            if (path == refused) throw refusal
            real.putObject(path, bytes)
        }
    }

    @Test
    fun a_refused_object_ends_the_upload_there_and_is_reported_not_thrown() =
        runTest {
            val local = heldStore(emptyDb())
            (1L..3L).forEach { local.putObject(own(it), byteArrayOf(it.toByte())) }
            val remote = Picky(MemoryStore(), own(2))

            val uploaded = GroupStore(local, remote).upload(me, 0)

            assertNotNull(uploaded.refused)
            assertEquals("nothing after the refused object goes out", listOf(own(1), own(2)), remote.tried)
            assertEquals("the cursor stays before the refused object", 0, uploaded.cursor)
        }

    @Test
    fun the_refused_object_goes_out_first_once_the_store_takes_it() =
        runTest {
            val local = heldStore(emptyDb())
            (1L..3L).forEach { local.putObject(own(it), byteArrayOf(it.toByte())) }
            val real = MemoryStore()
            val remote = Picky(real, own(2))
            val store = GroupStore(local, remote)

            val stuck = store.upload(me, 0)
            remote.refused = null
            val done = store.upload(me, stuck.cursor)

            assertNull(done.refused)
            assertTrue(done.cursor > 0)
            assertEquals(setOf(own(1), own(2), own(3)), real.objects.keys)
        }

    @Test
    fun pages_before_the_refused_one_are_kept_as_uploaded() =
        runTest {
            val local = heldStore(emptyDb())
            val pastThePage = 501L
            (1L..pastThePage).forEach { local.putObject(own(it), byteArrayOf(1)) }
            val remote = Picky(MemoryStore(), own(pastThePage))

            val uploaded = GroupStore(local, remote).upload(me, 0)

            assertNotNull(uploaded.refused)
            assertTrue("a full page went out and need not go again", uploaded.cursor > 0)
        }

    @Test
    fun a_refused_login_still_ends_the_sync() =
        runTest {
            val local = heldStore(emptyDb())
            local.putObject(own(1), byteArrayOf(1))
            val remote = Picky(MemoryStore(), own(1), StoreException.LoginRefused())

            val outcome = runCatching { GroupStore(local, remote).upload(me, 0) }

            assertTrue(outcome.exceptionOrNull() is StoreException.LoginRefused)
        }
}
