package io.github.aedev.flow.sync2

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.sync2.service.SyncTrigger
import io.github.aedev.flow.sync2.service.runs
import io.github.aedev.flow.sync2.service.workerTrigger
import org.junit.Test

class AutoSyncTest {
    @Test
    fun `with automatic sync on every trigger runs`() {
        assertThat(SyncTrigger.entries.filterNot { it.runs(autoSync = true) }).isEmpty()
    }

    @Test
    fun `with automatic sync off only Sync now and follow-ups run`() {
        assertThat(SyncTrigger.entries.filter { it.runs(autoSync = false) })
            .containsExactly(SyncTrigger.MANUAL, SyncTrigger.FOLLOW_UP)
    }

    @Test
    fun `worker runs map to the trigger that enqueued them`() {
        fun trigger(
            open: Boolean = false,
            lan: Boolean = false,
            soon: Boolean = false,
            announce: Boolean = false,
            automatic: Boolean = false,
        ) = workerTrigger(open, lan, soon, announce, automatic)

        assertThat(trigger()).isEqualTo(SyncTrigger.HOURLY)
        assertThat(trigger(lan = true)).isEqualTo(SyncTrigger.LAN_SEARCH)
        assertThat(trigger(open = true)).isEqualTo(SyncTrigger.OPEN)
        assertThat(trigger(soon = true, announce = true)).isEqualTo(SyncTrigger.MANUAL)
        assertThat(trigger(soon = true, automatic = true)).isEqualTo(SyncTrigger.LEAVE)
        assertThat(trigger(soon = true)).isEqualTo(SyncTrigger.FOLLOW_UP)
    }
}
