package io.github.aedev.flow.sync2

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.sync.crypto.SyncBytes
import io.github.aedev.flow.sync2.crypto.Purpose
import io.github.aedev.flow.sync2.crypto.encodePayload
import io.github.aedev.flow.sync2.crypto.purposeKey
import io.github.aedev.flow.sync2.engine.Session
import io.github.aedev.flow.sync2.live.LiveMessage
import io.github.aedev.flow.sync2.live.openLive
import io.github.aedev.flow.sync2.live.sealLive
import io.github.aedev.flow.sync2.store.Header
import io.github.aedev.flow.sync2.store.ObjectType
import io.github.aedev.flow.sync2.store.sealObject
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Live envelopes from the relay or a LAN peer: what isn't a valid message from another current device is dropped (spec/live.md). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class LiveFuzzTest {
    private val now = LiveMessage.Now("video", "Title", queue = listOf("video"))

    @Test
    fun any_change_to_a_live_envelope_is_dropped() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val a = Session.open(g.deviceDb(), g.store, g.a)
            val valid = Session.open(g.deviceDb(), g.store, g.b).sealLive(now, 7)
            assertEquals(now, a.openLive(valid)?.message)
            forAll(generate = { if (nextBoolean()) mutation(valid) else bytes(1024) }) { assertNull(a.openLive(it)) }
        }

    @Test
    fun a_sealed_envelope_that_is_not_a_live_message_is_dropped() =
        runTest {
            val g = LinkedGroup(this).apply { create() }
            val a = Session.open(g.deviceDb(), g.store, g.a)
            val key = purposeKey(g.epochKey, g.group, 0, Purpose.LIVE)
            forAll(generate = { if (nextBoolean()) json().toString() else text(64) }) { content ->
                val header = Header(ObjectType.LIVE, g.group, 0, g.b.did, 1, ByteArray(32))
                val envelope = sealObject(header, key, encodePayload(content.toByteArray()), g.b.sigSeed)
                a.openLive(envelope)?.let { assertEquals(SyncBytes.toHex(g.b.did), it.sender) }
            }
        }
}
