package io.github.aedev.flow.sync2

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync.transport.WsServer
import io.github.aedev.flow.sync2.crypto.MemberSecrets
import io.github.aedev.flow.sync2.data.LAN_STORE
import io.github.aedev.flow.sync2.data.LinkedDatabase
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.linking.Code
import io.github.aedev.flow.sync2.linking.DeviceMeta
import io.github.aedev.flow.sync2.linking.LanEndpoint
import io.github.aedev.flow.sync2.linking.Listening
import io.github.aedev.flow.sync2.linking.StoreLocation
import io.github.aedev.flow.sync2.linking.addDevice
import io.github.aedev.flow.sync2.linking.addScanned
import io.github.aedev.flow.sync2.linking.answerShown
import io.github.aedev.flow.sync2.linking.createLanGroup
import io.github.aedev.flow.sync2.linking.join
import io.github.aedev.flow.sync2.linking.newOffer
import io.github.aedev.flow.sync2.linking.readCode
import io.github.aedev.flow.sync2.store.GroupStore
import io.github.aedev.flow.sync2.store.LocalStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Groups that live on their devices alone (spec/live.md, LAN groups): created locally, paired over the LAN. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class LanGroupTest {
    private val meta = DeviceMeta("app", "Phone", "android")

    private class Device(
        val keys: MemberSecrets,
        val db: LinkedDatabase,
        val local: LocalStore,
    ) {
        suspend fun session() = Session.open(db, GroupStore(local, null), keys)
    }

    private fun TestScope.device(): Device = emptyDb().let { Device(newMember(), it, heldStore(it)) }

    private suspend fun loopback(): Listening {
        val server = WsServer("/flow-sync/2/pair")
        return Listening(server, listOf(LanEndpoint("127.0.0.1", server.start())))
    }

    private suspend fun TestScope.created(): Device = device().also { createLanGroup(it.db, it.local, it.keys, meta, LINK) }

    @Test
    fun a_group_on_this_network_is_held_by_its_creator_alone() =
        runTest {
            val a = created()
            assertEquals(
                LAN_STORE,
                a.db
                    .groups()
                    .group()
                    ?.storeUrl,
            )
            assertEquals(
                setOf(SyncBytes.toHex(a.keys.did)),
                a
                    .session()
                    .state.members
                    .filterValues { it.role.name == "DEVICE" }
                    .keys,
            )
        }

    @Test
    fun a_new_device_joins_a_lan_group_from_a_member_code() =
        runTest {
            val a = created()
            val n = device()
            val offer = newOffer(StoreLocation("lan"), "Phone", loopback())
            val code = readCode(offer.qr) as Code.Member
            assertEquals(null, code.storeUrl)
            withContext(Dispatchers.Default) {
                coroutineScope {
                    val added = async { addDevice(a.session(), null, offer, a.keys, LINK) { true } }
                    join(n.db, n.local, { null }, null, code, n.keys, meta, JOINER) { true }
                    added.await()
                }
            }
            assertEquals(
                LAN_STORE,
                n.db
                    .groups()
                    .group()
                    ?.storeUrl,
            )
            assertTrue(SyncBytes.toHex(n.keys.did) in n.session().state.members)
            assertEquals(a.session().state.head, n.session().state.head)
        }

    @Test
    fun a_tv_joins_a_lan_group_by_showing_its_code() =
        runTest {
            val a = created()
            val tv = device()
            val offer = newOffer(null, "TV", loopback())
            withContext(Dispatchers.Default) {
                coroutineScope {
                    val shown = async { answerShown(offer, tv.keys, meta) { true }.finish(tv.db, tv.local, null, JOINER) }
                    addScanned(a.session(), readCode(offer.qr) as Code.New, a.keys, LINK) { true }
                    shown.await()
                }
            }
            assertTrue(SyncBytes.toHex(tv.keys.did) in tv.session().state.members)
        }
}
