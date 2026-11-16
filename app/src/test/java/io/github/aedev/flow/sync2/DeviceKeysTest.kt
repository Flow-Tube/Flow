package io.github.aedev.flow.sync2

import io.github.aedev.flow.sync2.device.DamagedKeysException
import io.github.aedev.flow.sync2.device.SecretSlot
import io.github.aedev.flow.sync2.device.deviceKeys
import io.github.aedev.flow.sync2.device.freshDeviceKeys
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceKeysTest {
    private class MemorySlot(
        var value: String? = null,
    ) : SecretSlot {
        override suspend fun read(): String? = value

        override suspend fun write(value: String) {
            this.value = value
        }
    }

    @Test
    fun keys_are_created_once_and_read_back() =
        runTest {
            val slot = MemorySlot()
            val first = deviceKeys(slot)
            val again = deviceKeys(slot)
            assertArrayEquals(first.did, again.did)
            assertArrayEquals(first.sigSeed, again.sigSeed)
            assertArrayEquals(first.kemSecret, again.kemSecret)
        }

    @Test
    fun a_new_membership_gets_new_keys() =
        runTest {
            val slot = MemorySlot()
            val first = deviceKeys(slot)
            val fresh = freshDeviceKeys(slot)
            assertFalse(first.did.contentEquals(fresh.did))
            assertArrayEquals(fresh.did, deviceKeys(slot).did)
        }

    @Test
    fun damaged_keys_are_reported_and_kept() =
        runTest {
            for (stored in listOf("not json", "", """{"sig":"AA","kem":"AA"}""")) {
                val slot = MemorySlot(stored)
                assertTrue(runCatching { deviceKeys(slot) }.exceptionOrNull() is DamagedKeysException)
                assertEquals(stored, slot.value)
            }
        }
}
