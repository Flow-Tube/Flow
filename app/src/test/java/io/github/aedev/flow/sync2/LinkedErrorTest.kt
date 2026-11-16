package io.github.aedev.flow.sync2

import io.github.aedev.flow.sync2.crypto.ProtocolError
import io.github.aedev.flow.sync2.crypto.ProtocolException
import io.github.aedev.flow.sync2.device.DamagedKeysException
import io.github.aedev.flow.sync2.engine.LinkedException
import io.github.aedev.flow.sync2.service.LinkedError
import io.github.aedev.flow.sync2.service.linkedError
import io.github.aedev.flow.sync2.store.StoreException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class LinkedErrorTest {
    @Test
    fun failures_map_to_what_the_user_is_told() {
        assertEquals(LinkedError.UNREACHABLE, StoreException.Network(IOException()).linkedError())
        assertEquals(LinkedError.PROTOCOL, ProtocolException(ProtocolError.SIGNATURE).linkedError())
        assertEquals(LinkedError.REMOVED, LinkedException.Removed().linkedError())
        assertEquals(LinkedError.REMOVED, StoreException.SignInRefused().linkedError())
        assertEquals(LinkedError.LOCAL, DamagedKeysException().linkedError())
        assertEquals(LinkedError.BUSY, StoreException.Refused(429, "rate_limited").linkedError())
        assertEquals(LinkedError.REFUSED, StoreException.Refused(401, "unauthorized").linkedError())
        assertEquals(LinkedError.GROUP_MISSING, StoreException.Refused(503, "unclaimed").linkedError())
        assertEquals(LinkedError.STOPPED, LinkedException.Stopped().linkedError())
        assertEquals(LinkedError.RESTORE_UNFINISHED, LinkedException.RestoreUnfinished().linkedError())
    }

    @Test
    fun only_passing_trouble_is_retried_on_its_own() {
        assertTrue(LinkedError.UNREACHABLE.retryable)
        assertTrue(LinkedError.BUSY.retryable)
        assertFalse(LinkedError.REMOVED.retryable)
        assertFalse(LinkedError.INSTANCE_CHANGED.retryable)
    }
}
