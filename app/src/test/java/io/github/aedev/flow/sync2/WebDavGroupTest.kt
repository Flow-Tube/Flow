package io.github.aedev.flow.sync2

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync2.data.WEBDAV_STORE
import io.github.aedev.flow.sync2.data.webDavUrl
import io.github.aedev.flow.sync2.device.WebDavLogin
import io.github.aedev.flow.sync2.engine.LinkedException
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.linking.Code
import io.github.aedev.flow.sync2.linking.DeviceMeta
import io.github.aedev.flow.sync2.linking.StoreLocation
import io.github.aedev.flow.sync2.linking.addDevice
import io.github.aedev.flow.sync2.linking.createWebDavGroup
import io.github.aedev.flow.sync2.linking.join
import io.github.aedev.flow.sync2.linking.newOffer
import io.github.aedev.flow.sync2.linking.readCode
import io.github.aedev.flow.sync2.linking.restoreWebDavGroup
import io.github.aedev.flow.sync2.store.FileLayout
import io.github.aedev.flow.sync2.store.GroupStore
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Groups that live in a WebDAV folder: created, joined with the login handed over in the welcome, and restored. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class WebDavGroupTest {
    private val dav = WebDavStandIn()
    private val meta = DeviceMeta("app-a", "Desktop A", "android")
    private val login = WebDavLogin(WebDavStandIn.USER, WebDavStandIn.PASSWORD)

    @After
    fun stop() = dav.close()

    @Test
    fun a_group_is_created_in_an_empty_folder_only() =
        runTest {
            val db = emptyDb()
            val (words, conditional) = createWebDavGroup(db, dav.store(), newMember(), meta, LINK)
            assertEquals(24, words.split(' ').size)
            assertTrue(conditional)
            val record = checkNotNull(db.groups().group())
            assertEquals(WEBDAV_STORE + dav.url, record.storeUrl)
            assertEquals("""{"format":"FLOW-SYNC/2","group":"${record.groupId}"}""", dav.file("store.json")?.decodeToString())
            assertEquals(1, FileLayout(dav.store(), "").rosterAfter(-1).size)

            val error = runCatching { createWebDavGroup(emptyDb(), dav.store(), newMember(), meta, LINK) }.exceptionOrNull()
            assertTrue("$error", error is LinkedException.ServerHasGroup)
        }

    @Test
    fun a_new_device_joins_through_the_folder_and_gets_its_login_in_the_welcome() =
        runTest {
            val a = newMember()
            val dbA = emptyDb()
            createWebDavGroup(dbA, dav.store(), a, meta, LINK)
            val didA = SyncBytes.toHex(a.did)
            val storeA = GroupStore(heldStore(dbA), FileLayout(dav.store(), didA)).also { it.download(didA, 0, objects = false) }
            val session = Session.open(dbA, storeA, a)

            val offer = newOffer(StoreLocation("webdav", dav.url), meta.name, null)
            assertFalse("a code never carries the login", WebDavStandIn.PASSWORD in offer.qr)
            val code = readCode(offer.qr) as Code.Member
            val newcomer = newMember()
            val dbN = emptyDb()
            val welcomed =
                coroutineScope {
                    val joined =
                        async {
                            // The user entered the login before scanning, so the folder's mailbox may carry the pairing.
                            val mailbox = FileLayout(dav.store(), SyncBytes.toHex(newcomer.did))
                            join(
                                dbN,
                                heldStore(dbN),
                                { it.remote(newcomer) },
                                mailbox,
                                code,
                                newcomer,
                                DeviceMeta("app-n", "Phone", "android"),
                                JOINER,
                            ) { true }
                        }
                    addDevice(session, storeA.server, offer, a, login) { true }
                    joined.await()
                }
            assertEquals(login, welcomed.login)
            assertEquals(dav.url, dbN.groups().group()?.webDavUrl)
            assertTrue(
                SyncBytes.toHex(newcomer.did) in FileLayout(dav.store(), "").rosterAfter(-1).flatMap { it.add.orEmpty() }.map { it.did },
            )
        }

    @Test
    fun a_group_is_restored_from_the_folder_with_the_recovery_words() =
        runTest {
            val (words, _) = createWebDavGroup(emptyDb(), dav.store(), newMember(), meta, LINK)
            val db = emptyDb()
            val newWords = restoreWebDavGroup(db, dav.store(), words, newMember(), meta)
            assertNotNull(db.groups().group()?.webDavUrl)
            assertFalse(newWords == words)
            val error = runCatching { restoreWebDavGroup(emptyDb(), dav.store(), words, newMember(), meta) }.exceptionOrNull()
            assertTrue("used words don't restore twice: $error", error is LinkedException.RecoveryUsed)
        }
}
